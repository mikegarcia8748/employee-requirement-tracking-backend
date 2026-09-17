# Running and deploying the Employee Requirements Tracker

The step-by-step guide ERT-1100 deferred until a target environment existed. ERT-1200 created one.

| Environment | Runs on | `APP_ENV` | Database | Who deploys |
|---|---|---|---|---|
| **Local** | your machine | `dev` | in-memory H2, or compose Postgres | you, `./kotlin run` |
| **UAT** | Cloud Run `ert-uat` | `staging` | Cloud SQL `ert_uat` | automatic, on merge to `main` |
| **Production** | Cloud Run `ert-prod` | `production` | Cloud SQL `ert` | a human, via the `deploy-prod` workflow |

Two things to know before anything else, because both surprise people:

- **The application reads `System.getenv` directly. There is no dotenv library on the classpath.**
  A `.env` file sitting next to the jar does nothing. It has to be exported into the shell, passed
  by compose, or set on the Cloud Run service.
- **Since ERT-1120, an unset `APP_ENV` means production**, and production refuses to start without
  `JWT_SECRET` and `TOKEN_PEPPER`. `dev` is opt-in. This is deliberate: the default used to run the
  other way, and a deployment that forgot the variable came up looking healthy and fully permissive.

---

## 1. Local development

### 1.1 The fastest path — no configuration at all

```bash
set -a; . ./.env.dev; set +a; ./kotlin run
```

In-memory H2, wiped when the process exits. An ephemeral JWT key and token pepper, so any sign-in
token or upload link stops working at restart. `/openapi`, `/swagger` and `/metrics` are open.

`set -a` marks every following assignment for export and `set +a` stops. Without it the values are
shell variables, the process sees nothing, and the server refuses to start — which looks exactly
like the file not working.

The server answers on <http://localhost:8080>. Check it:

```bash
curl -fsS http://localhost:8080/health
```

The first nine lines of the log are the startup summary, and they name the resolved state of every
environment-gated control. If you are ever unsure what mode something came up in, read those rather
than the configuration.

### 1.2 Against a real Postgres — what most development should use

Postgres is the production database and H2 is what everyone actually runs, so without this nobody
exercises the real one until UAT does. `MigrationTest` checks portability, but H2-in-PostgreSQL-mode
is not a perfect oracle and was never meant to be.

```bash
docker compose up -d
```

That starts **only** Postgres, on `127.0.0.1:55432` — port 55432 so it does not fight whatever
Postgres is already installed on your machine. Then:

```bash
set -a; . ./.env.local-pg; set +a; ./kotlin run
```

Flyway migrates on startup, every time. To start over:

```bash
docker compose down -v && docker compose up -d
```

### 1.3 Exactly what CI builds

> **The first `docker build` is slow — allow 30–45 minutes, and do not assume it has hung.** The
> Amper wrapper downloads its toolchain inside the build stage, and that distribution is **224 MB**.
> Measured from a container on this network it arrives at roughly 270 KB/s, which is about 14 minutes
> for the toolchain alone, before the JDK and the ~176 dependency jars. The build prints
> `Downloading Kotlin Toolchain distribution v0.12.0...` once and then says nothing until it
> finishes, which looks identical to a hang.
>
> This is exactly what the BuildKit cache mount in the `Dockerfile` is for: every build after the
> first reuses it and takes seconds. Use `docker build --progress=plain` when you want to see which
> step you are on — the default progress renderer collapses the output.
>
> If a build is cancelled mid-download, the next one may sit on
> `Another Kotlin CLI instance (pid N) is downloading…` forever. The wrapper decides whether the
> holder is alive with `kill -0 <pid>`, and that PID belongs to a **different container's** namespace,
> where it usually exists as something unrelated. The `Dockerfile` clears stale lock files before
> invoking the wrapper for precisely this reason; if you hit it another way, clear the cache with
> `docker builder prune --filter type=exec.cachemount`.

```bash
docker compose --profile app up --build
```

Builds the image and runs it against the compose Postgres. Slower to iterate on — you rebuild the
image for every change — so use it to reproduce a container-only problem, not to develop.

The app waits for Postgres's healthcheck before starting, because **it has no degraded mode**: it
does not start at all if the database is unreachable. That is by design (PRD §11); the healthcheck
gate is what stops it being mistaken for a broken image on first run.

### 1.4 Signing in

The `users` table starts empty and nobody can sign in. Set both bootstrap variables and restart:

```bash
HR_BOOTSTRAP_EMAIL=hr.admin@example.test \
HR_BOOTSTRAP_PASSWORD=change-me-at-least-12-chars \
  bash -c 'set -a; . ./.env.dev; set +a; ./kotlin run'
```

The account is created **only when the table is empty**, lands with `passwordChangeRequired`, and
must change its password before any other route will answer. Changing these variables later does
nothing — use `POST /api/users/{id}/reset-password`.

### 1.5 Build and test

```bash
./kotlin build
```

```bash
./kotlin test
```

Tests run on in-memory H2 and need no external service. `module.yaml` sets `APP_ENV=dev` for the
test JVM through `settings.jvm.test.extraEnvironment` — a real environment variable, the same input
production reads, rather than a test-only backdoor into a security control.

---

## 2. One-time GCP setup

Run all of this **once**, in [Cloud Shell](https://console.cloud.google.com) — it needs `gcloud` and
Owner-level permissions. Nothing in the pipeline does any of it.

### 2.1 Variables and APIs

```bash
export PROJECT_ID=REPLACE_ME
export REGION=asia-southeast1
export REPO=REPLACE_OWNER/PG-EmployeeRequirementsTracker
export PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
gcloud config set project "$PROJECT_ID"
```

`asia-southeast1` is Singapore, the nearest Cloud Run region to the Philippines; `asia-east1`
(Taiwan) is the alternative. Confirm with whoever owns the GCP organisation — it is a one-line change
now and a painful migration later, and PRD §14 Q5 (data residency) may constrain it.

```bash
gcloud services enable \
  run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com \
  secretmanager.googleapis.com iamcredentials.googleapis.com \
  compute.googleapis.com servicenetworking.googleapis.com \
  cloudresourcemanager.googleapis.com
```

### 2.2 Artifact Registry

```bash
gcloud artifacts repositories create ert \
  --repository-format=docker --location="$REGION" \
  --description="Employee Requirements Tracker images"
```

```bash
gcloud artifacts repositories update ert --location="$REGION" --immutable-tags
```

Immutable tags are what turn "we tag with the commit SHA" from a convention into a guarantee: a tag,
once pushed, can never be moved to point at different bytes. It is also why there is no floating
`:latest` or `:uat` tag anywhere in this design.

### 2.3 Workload Identity Federation — keyless

No service-account JSON key is created anywhere in this runbook. That is the single biggest thing
this setup buys: the credential that would otherwise leak does not exist.

```bash
gcloud iam workload-identity-pools create github \
  --location=global --display-name="GitHub Actions"
```

```bash
gcloud iam workload-identity-pools providers create-oidc github \
  --location=global --workload-identity-pool=github \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner,attribute.environment=assertion.environment,attribute.ref=assertion.ref" \
  --attribute-condition="assertion.repository_owner == '${REPO%%/*}'"
```

> **The `--attribute-condition` is mandatory, not optional hardening.** Without it, any GitHub
> repository on earth can mint tokens against this pool. GCP will refuse to create a provider with no
> condition for precisely this reason.

### 2.4 Four service accounts

Two deployers and two runtimes, so that "UAT cannot touch production" and "production cannot build an
image" are IAM facts rather than conventions.

```bash
for sa in gh-deploy-uat gh-deploy-prod ert-uat-run ert-prod-run; do
  gcloud iam service-accounts create "$sa" --display-name "$sa"
done
```

```bash
export POOL="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github"
```

UAT's deployer — any workflow run in this repository may impersonate it:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  "gh-deploy-uat@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/${POOL}/attribute.repository/${REPO}"
```

Production's deployer — **only** a job running in the GitHub Environment named `production`:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  "gh-deploy-prod@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/iam.workloadIdentityUser \
  --member="principal://iam.googleapis.com/${POOL}/subject/repo:${REPO}:environment:production"
```

> `principal://…/subject/` is an exact match on the OIDC `sub` claim, which for an environment job is
> `repo:OWNER/REPO:environment:production`. **This — not the workflow file — is what makes the
> Environment's required reviewers a real control.** Someone who edits `deploy-prod.yml` to drop the
> `environment:` block does not bypass the approval; they get a job that cannot authenticate at all.

### 2.5 Deployer permissions

```bash
for sa in gh-deploy-uat gh-deploy-prod; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${sa}@${PROJECT_ID}.iam.gserviceaccount.com" \
    --role=roles/run.developer
done
```

`roles/run.developer` at project level is needed to **create** a service the first time. Once both
services exist, replace it with per-service bindings and remove the project-level grant.

A deployer must be allowed to hand the runtime identity to the service:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  "ert-uat-run@${PROJECT_ID}.iam.gserviceaccount.com" \
  --member="serviceAccount:gh-deploy-uat@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/iam.serviceAccountUser
```

```bash
gcloud iam service-accounts add-iam-policy-binding \
  "ert-prod-run@${PROJECT_ID}.iam.gserviceaccount.com" \
  --member="serviceAccount:gh-deploy-prod@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/iam.serviceAccountUser
```

Registry access — **UAT writes, production only reads**. This is the enforcement of "production
promotes, production never builds":

```bash
gcloud artifacts repositories add-iam-policy-binding ert --location="$REGION" \
  --member="serviceAccount:gh-deploy-uat@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.writer
```

```bash
gcloud artifacts repositories add-iam-policy-binding ert --location="$REGION" \
  --member="serviceAccount:gh-deploy-prod@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role=roles/artifactregistry.reader
```

### 2.6 Network and Cloud SQL

Direct VPC egress to a **private IP**. Of the three ways to reach Cloud SQL from Cloud Run this is
the only one needing no code and no new dependency — `DatabaseConfig` already selects the driver on
`url.startsWith("jdbc:postgresql")` — it costs nothing (a Serverless VPC Access *connector* would be
a standing monthly charge; Direct VPC egress has no VM), and it lets the instance drop its public IP
entirely. The argument that decides it: **Flyway runs synchronously before Netty binds and the
service has no degraded mode**, so putting the Cloud SQL Admin API on the boot path — which the
socket-factory alternative does — would convert an API hiccup into a total outage.

```bash
gcloud compute networks create ert-vpc --subnet-mode=custom
```

```bash
gcloud compute networks subnets create ert-run \
  --network=ert-vpc --region="$REGION" --range=10.20.0.0/24
```

```bash
gcloud compute addresses create google-managed-services-ert \
  --global --purpose=VPC_PEERING --prefix-length=16 --network=ert-vpc
```

```bash
gcloud services vpc-peerings connect \
  --service=servicenetworking.googleapis.com \
  --ranges=google-managed-services-ert --network=ert-vpc
```

```bash
gcloud sql instances create ert-db \
  --database-version=POSTGRES_17 \
  --tier=db-custom-1-3840 \
  --region="$REGION" \
  --network="projects/${PROJECT_ID}/global/networks/ert-vpc" \
  --no-assign-ip \
  --availability-type=ZONAL \
  --storage-auto-increase \
  --backup-start-time=18:00 \
  --enable-point-in-time-recovery
```

`POSTGRES_17` matches `postgres:17` in `docker-compose.yml`, so local, UAT and production all share a
major version.

One instance, two databases. UAT and production share the machine but not the data, which is right at
this scale — and `MAIL_ENABLED=false` in UAT is the control that makes sharing safe.

```bash
gcloud sql databases create ert     --instance=ert-db
gcloud sql databases create ert_uat --instance=ert-db
```

Now read the private IP and put it into **both** `deploy/*.env.yaml` files, replacing
`REPLACE_WITH_CLOUD_SQL_PRIVATE_IP`:

```bash
gcloud sql instances describe ert-db --format='value(ipAddresses[0].ipAddress)'
```

### 2.7 Secrets

```bash
new_secret() {  # new_secret <name> <value>
  printf '%s' "$2" | gcloud secrets create "$1" --data-file=- --replication-policy=automatic
}
```

> `printf '%s'`, never `echo`. A trailing newline inside `TOKEN_PEPPER` or an SMTP password is an
> afternoon you will not get back.

```bash
for env in prod uat; do
  new_secret "ert-${env}-jwt-secret"   "$(openssl rand -base64 48)"
  new_secret "ert-${env}-token-pepper" "$(openssl rand -base64 48)"
  new_secret "ert-${env}-db-password"  "$(openssl rand -base64 32)"
done
```

UAT and production get **different** values. A shared pepper would mean a link issued in UAT resolves
in production.

Create the database users with those passwords:

```bash
gcloud sql users create ert --instance=ert-db \
  --password="$(gcloud secrets versions access latest --secret=ert-prod-db-password)"
```

```bash
gcloud sql users create ert_uat --instance=ert-db \
  --password="$(gcloud secrets versions access latest --secret=ert-uat-db-password)"
```

Grant the accessor role **per secret** to the runtime service account, never project-wide:

```bash
for env in prod uat; do
  for s in jwt-secret token-pepper db-password; do
    gcloud secrets add-iam-policy-binding "ert-${env}-${s}" \
      --member="serviceAccount:ert-${env}-run@${PROJECT_ID}.iam.gserviceaccount.com" \
      --role=roles/secretmanager.secretAccessor
  done
done
```

Cloud Run resolves a secret at **revision creation** and injects it as an ordinary environment
variable, so `System.getenv` picks it up with no code change — and rotation requires a redeploy.
Given what `TOKEN_PEPPER` means, that is correct: it should be a deliberate act, not something that
happens at the next instance start.

### 2.8 GitHub repository configuration

**Settings → Secrets and variables → Actions → Variables** — variables, not secrets, because none of
these is confidential and you want to see them in the log when a deploy misfires:

| Name | Value |
|---|---|
| `GCP_PROJECT_ID` | your project id |
| `GCP_PROJECT_NUMBER` | `$PROJECT_NUMBER` from §2.1 |
| `GCP_REGION` | `asia-southeast1` |

**Settings → Environments**

- `uat` — no protection.
- `production` — **Required reviewers**, and **Deployment branches: `main` only**.

**Settings → Branches → `main`** — require the `build` check and require a pull-request review.

---

## 3. The first deployment

1. Fill in the placeholders in `deploy/uat.env.yaml` and `deploy/prod.env.yaml` — the Cloud SQL
   private IP and the real hostnames. `grep -rn REPLACE deploy/` finds them all.
2. Uncomment `HR_BOOTSTRAP_EMAIL` in both files, and add the bootstrap password as a secret:

   ```bash
   new_secret ert-prod-hr-bootstrap-password "$(openssl rand -base64 24)"
   ```

   Add it to the service's `--set-secrets` list in the workflow for the first deploy only.
3. Merge to `main`. The `deploy-uat` workflow builds, pushes, deploys and verifies `/health`.
4. Reach UAT — it is IAM-gated, so there is no public URL:

   ```bash
   gcloud run services proxy ert-uat --region="$REGION" --port=8080
   ```

   That gives an authenticated tunnel on `localhost:8080` with no load balancer, no IAP and no cost.
   For a handful of HR staff it is the right amount of infrastructure.
5. Add the startup probes once the services exist:

   ```bash
   gcloud run services update ert-prod --region="$REGION" \
     --startup-probe=httpGet.path=/health,initialDelaySeconds=0,periodSeconds=5,failureThreshold=24,timeoutSeconds=3 \
     --liveness-probe=httpGet.path=/health,periodSeconds=30,failureThreshold=3,timeoutSeconds=3
   ```

   If your `gcloud` rejects that flag grammar, use
   `gcloud run services describe ert-prod --format=export > svc.yaml`, edit the probe block, and
   `gcloud run services replace svc.yaml`.

### The `HR_BOOTSTRAP_PASSWORD` lifecycle

It is read **only when the `users` table is empty**. Once the first admin has signed in and changed
their password it does nothing — and a live credential wired into a service definition for no
remaining benefit is a standing risk. So:

```bash
gcloud run services update ert-prod --region="$REGION" \
  --remove-secrets=HR_BOOTSTRAP_PASSWORD
```

```bash
gcloud secrets delete ert-prod-hr-bootstrap-password
```

Remove the `HR_BOOTSTRAP_EMAIL` line from `deploy/prod.env.yaml` in the same change.

---

## 4. Deploying

**UAT** is automatic: merge to `main`.

**Production** is the `deploy-prod` workflow, run manually from the Actions tab. Leave the digest
input empty and it promotes whatever UAT is currently serving — read off the live service, so
"promote what UAT tested" is literally true rather than an assumption about which workflow ran last.

It refuses if the resolved image is not digest-pinned, and it rolls out as a canary: the candidate
takes no traffic, `/health` is smoke-tested against a tag URL no user has, and only then does traffic
shift. That gives Flyway exactly one instance to migrate on, and it catches a boot failure — a bad
secret, an unreachable database, a migration that will not apply — while 100 % of traffic is still on
the old revision.

### Rollback

```bash
gcloud run revisions list --service=ert-prod --region="$REGION"
```

```bash
gcloud run services update-traffic ert-prod --region="$REGION" \
  --to-revisions=REPLACE_WITH_PREVIOUS_REVISION=100
```

> **Traffic rolls back in seconds. The migration does not.**
>
> Flyway has no undo. Every migration must therefore be backward-compatible with the revision before
> it: add columns nullable, never rename or drop in the same release that starts using the new shape,
> expand then contract. This is a standing rule, recorded in
> [architecture.md](architecture.md) §14 — not advice.

---

## 5. Why the Cloud Run settings are what they are

| Setting | prod | uat | Reason |
|---|---|---|---|
| `--max-instances` | 4 | 2 | **The most consequential flag here.** The default is 100; at the code's default pool of 10 that is 1000 Postgres backends from one service — enough to exhaust any tier, during a traffic spike, which is the worst possible moment. |
| `DATABASE_MAX_POOL_SIZE` | 5 | 5 | See the budget below. |
| `--min-instances` | 1 | 0 | Production: users never meet a cold start, Flyway has already run, the pool is warm. UAT: cost. |
| `--no-cpu-throttling` | **yes** | no | Cloud Run throttles CPU to near zero between requests by default, which stops **HikariCP's housekeeper** — the symptom is `connection reset by peer` on the first request after a quiet period. This breaks *today*, before any new feature. It will also break ERT-1010's outbox poller and ERT-1020's scheduler. |
| `--cpu-boost` | yes | yes | Extra CPU during startup; roughly halves the JVM portion of a cold start. Free. |
| `--concurrency` | 80 | 80 | Counter-intuitive: *lowering* concurrency multiplies instances, which multiplies connections. Revisit when ERT-710 lands — 80 concurrent multipart uploads will not fit in 1 GiB. |
| `--cpu` / `--memory` | 1 / 1Gi | 1 / 512Mi | |
| `--allow-unauthenticated` | yes | **no** | The portal must be reachable by hires. UAT is IAM-gated. |
| `--service-account` | `ert-prod-run@` | `ert-uat-run@` | Never the default compute service account, which is Editor on the whole project. |
| `--timeout` | 120 | 120 | The default 300 s just means a stuck request occupies a concurrency slot for five minutes. |

### The connection budget

```
peak = Σ over services (max_instances × DATABASE_MAX_POOL_SIZE)
     + Flyway's own short-lived connections during a deploy
     + human and admin sessions
     + Cloud SQL's reserved superuser slots
must stay ≤ 0.8 × max_connections
```

Flyway gets its own connections rather than sharing the pool, so that term is a genuine addition
during a deploy, not an accounting artefact.

| Term | Value |
|---|---|
| prod: 4 × 5 | 20 |
| uat: 2 × 5 | 10 |
| Flyway, both services deploying at once | 6 |
| humans, `psql`, a migration tool | 5 |
| Cloud SQL reserved | 3 |
| **Peak** | **44** |

Check the real ceiling rather than trusting that:

```bash
gcloud sql connect ert-db --user=ert --database=ert --quiet
```

then `SHOW max_connections;`.

### `/health` is a static 200 and should stay that way

It does not touch the database, and that is correct for both probes. For a **startup** probe it is
sufficient because Netty only binds after Flyway succeeds — "the port is listening" already implies
"migrated". For a **liveness** probe it is what you want: a database blip must not cause Cloud Run to
kill and restart every instance, which would turn a 30-second hiccup into a thundering-herd cold-start
storm. If a database-aware readiness signal is ever wanted for humans, add `/health/ready` and leave
the probes pointed at `/health`.

### `/metrics` stays behind HR authentication

A Prometheus scrape body enumerates every route, every status code and every error count — which is
reconnaissance against exactly the portal endpoints the security audit is about. Cloud Run already
exports request count, latency percentiles, instance count, CPU, memory and container startup latency
with no configuration. PRD §13's business indicators (PIN failure rate, sessions blocked by lockout,
upload error rate) should become **log-based metrics** over the JSON logs from ERT-1250 — a counter on
`jsonPayload.formattedMessage` or a dedicated field is a few clicks, alertable, and needs no scrape
target.

---

## 6. Things that will bite

**UAT does not faithfully test background work.** It runs with CPU throttling and `min-instances=0`,
so an in-process timer does not fire while the service is idle. When ERT-1010's outbox poller or
ERT-1020's scheduler is under test, flip UAT for the duration:

```bash
gcloud run services update ert-uat --region="$REGION" --no-cpu-throttling --min-instances=1
```

**The deployment is multi-instance, and nothing may assume one process.** `--max-instances 1` is a
per-revision ceiling, not a mutex — during any rollout the old revision's instance and the new one
both exist. ERT-660's rate limiter is therefore coarse shaping only (effective limit
`configured × instances`), and anything security-bearing must be counted in the database, as the PIN
counters already are. Recorded in full on ERT-1120.

**The token pepper cannot be rotated.** Rotating `TOKEN_PEPPER` changes every digest, so every live
link and portal session stops resolving at once, and there is no re-issue flow — recovery means
re-inviting every in-flight hire by hand. Treat it as **permanent for the life of an environment**
until ERT-1130 ships a two-pepper verification window.

**A JWT is not revoked by deactivating the account.** The verifier does not resolve `sub` against the
`users` table, so deactivation and password reset take up to `JWT_TTL_MINUTES` to bite. That is an
accepted, documented trade; shortening the TTL shortens the window.

**Uploads cannot use the filesystem adapter.** ERT-710 has not shipped one yet, and when it does it
must refuse to run outside dev: on Cloud Run every write outside the image layers goes to an
in-memory tmpfs charged against the memory limit, with no eviction, and it is per-instance. PRD §14
Q20 already chose GCS with V4 signed URLs for production.

**Migrations run in-process at startup.** Fine for the six DDL and seed migrations that exist. The
first one that *rewrites data* needs ERT-1285 — a migrate-only entry point run as a Cloud Run Job
before the deploy — because a multi-minute migration inside a startup probe is a flapping deploy.

---

## 7. Troubleshooting

| Symptom | Cause |
|---|---|
| `TOKEN_PEPPER must be set outside dev` on a laptop | `APP_ENV` is unset, which means production since ERT-1120. Use `.env.dev`. |
| `.env.dev` "does nothing" | You sourced it without `set -a`, so the values never reached the process. Nothing loads these files. |
| The container starts and loses all data | `DATABASE_URL` was unset. Since ERT-1241 that refuses outside dev; if you see this in dev, it is the H2 fallback working as designed. |
| `connection reset by peer` on the first request after a quiet period | `maxLifetime`/`keepaliveTime` exceeded a middlebox idle drop, or CPU throttling stopped Hikari's housekeeper. Production sets `--no-cpu-throttling` for this. |
| The deploy flaps; the startup probe never passes | Usually Flyway waiting on the advisory lock, or a migration that cannot apply. Read the revision's logs; the canary in `deploy-prod` exists so this happens before traffic moves. |
| `Error: could not mint token` / WIF failures | The workflow is missing `permissions: id-token: write`, or the job dropped its `environment:` block and no longer matches the IAM binding. |
| `/openapi` returns 404 in UAT or production | Intended since ERT-1240. The generator is dev-only; use `/swagger` and `/swagger/documentation.yaml`. |
| Cloud Monitoring cannot scrape `/metrics` | Intended. See §5. |

To see what a running service actually resolved:

```bash
gcloud run services logs read ert-prod --region="$REGION" --limit=50
```

The startup summary is the first nine lines after each cold start and names every gated control.
