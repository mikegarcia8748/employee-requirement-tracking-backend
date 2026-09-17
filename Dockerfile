# syntax=docker/dockerfile:1.10

###############################################################################
# Stage 1 — build with the Amper wrapper (ERT-1220)
#
# Ubuntu, not Alpine. The `./kotlin` wrapper downloads a Temurin JDK at build
# time and that JDK is glibc-linked: on musl it fails with an opaque "not found"
# on a binary that plainly exists, which is a bad half-hour to spend.
#
# The base image's own JDK is reused as the launcher for the Kotlin CLI
# (KOTLIN_CLI_JAVA_HOME), which saves one download. It does NOT decide the
# compilation target — `settings.jvm.release` in module.yaml does, and pinning it
# is what lets the runtime stage below pin a JRE independently (ERT-1210).
###############################################################################
FROM eclipse-temurin:25-jdk-noble AS build

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*

ENV KOTLIN_CLI_JAVA_HOME=/opt/java/openjdk \
    KOTLIN_CLI_NO_WELCOME_BANNER=1

WORKDIR /src

# --- Layer 1: the toolchain --------------------------------------------------
# HOME is /root here, so the wrapper's Linux default shared cache root is
# /root/.cache/JetBrains/Kotlin — one directory holding both the CLI dist and
# the ~176 dependency jars. One mount covers both; no --shared-cache-dir needed.
#
# Copying only the wrapper first means this layer is invalidated solely by a
# toolchain bump, which is the single slowest thing to redo -- and "slowest" is
# not a figure of speech: the toolchain distribution is 224 MB, measured at about
# 270 KB/s from a container on the network this was built on, so roughly a quarter
# of an hour before the JDK and the ~176 dependency jars even start. A cold build
# is a 30-45 minute affair; every build after it reuses the cache mount and takes
# seconds. That ratio is the entire justification for this layer split.
#
# THE `rm -f` IS NOT DEFENSIVE CLUTTER. The wrapper takes a lock file while it
# downloads the toolchain, and waits on it if another instance holds one --
# deciding "is that instance still alive?" with `kill -0 <pid>`. A cache mount
# survives an interrupted build, so a cancelled `docker build` leaves its lock
# behind, and the PID it names is from a DIFFERENT container's namespace. In a
# fresh container that PID usually exists as some unrelated process, `kill -0`
# succeeds, and the build waits for a download that will never finish. Observed,
# not theorised: it cost half an hour of a build that looked like a slow network.
COPY kotlin kotlin.bat ./
RUN --mount=type=cache,id=ert-kotlin,target=/root/.cache/JetBrains/Kotlin,sharing=locked \
    set -eux; \
    rm -f /root/.cache/JetBrains/Kotlin/cli/download-*.lock; \
    ./kotlin --version

# --- Layer 2: the project model ----------------------------------------------
# Amper offers no resolve-only entry point, so this split cannot pre-warm the
# dependency jars by itself — the cache mount is what makes rebuilds fast. The
# split still earns its place by keeping layer 1 alive across source edits.
COPY module.yaml libs.versions.toml ./

# --- Layer 3: sources, least-churn first -------------------------------------
COPY resources ./resources
COPY src ./src

# --- Layer 4: build ----------------------------------------------------------
# `build/` is a cache mount, so the jar must be copied OUT of it inside this same
# RUN or it will not exist in the resulting layer.
#
# The output path is DISCOVERED rather than hardcoded. Amper writes it to
# build/tasks/_<module>_executableJarJvm/<module>-jvm-executable.jar today, but
# that is an implementation detail of a task this repository has run only since
# ERT-1200. Two assertions turn a silently wrong artefact into a build failure.
#
# Note the first one checks Start-Class, NOT Main-Class. Amper's executable-jar
# uses the Spring Boot loader layout, so Main-Class is JarLauncher and the real
# entry point is Start-Class. Asserting Main-Class would fail against a perfectly
# good jar.
RUN --mount=type=cache,id=ert-kotlin,target=/root/.cache/JetBrains/Kotlin,sharing=locked \
    --mount=type=cache,id=ert-build,target=/src/build,sharing=locked \
    set -eux; \
    rm -f /root/.cache/JetBrains/Kotlin/cli/download-*.lock; \
    ./kotlin package --format=executable-jar --variant=release; \
    jar="$(find build -type f -name '*-executable.jar' | head -n1)"; \
    if [ -z "$jar" ]; then \
      echo 'FATAL: no executable-jar output found. Candidates:'; \
      find build -type f -name '*.jar' -exec ls -l {} + || true; \
      exit 1; \
    fi; \
    mkdir -p /out; \
    cp "$jar" /out/app.jar; \
    unzip -p /out/app.jar META-INF/MANIFEST.MF | tr -d '\r' \
      | grep -q 'Start-Class: com.pgsystem.employee.requirement.tracker.MainKt'; \
    [ "$(stat -c%s /out/app.jar)" -gt 5000000 ] \
      || { echo "FATAL: $(stat -c%s /out/app.jar) bytes — this is the thin jar, not the fat one"; exit 1; }

###############################################################################
# Stage 2 — runtime
#
# JRE 21 because module.yaml pins `settings.jvm.release: 21`. If that pin ever
# moves, this tag moves with it — that coupling is the whole reason the pin
# exists rather than being left to track the toolchain's JDK.
###############################################################################
FROM eclipse-temurin:21-jre-noble AS runtime

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# ERT-1240 stopped swagger-codegen running outside dev, so nothing should write
# here any more. Created and owned anyway: if that gating ever regresses, the
# result should be a wasted write rather than a crash on boot.
RUN mkdir -p /app/build/openapi-docs && chown -R app:app /app

COPY --from=build --chown=app:app /out/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# JAVA_TOOL_OPTIONS rather than a shell-wrapped ENTRYPOINT: the JVM picks it up
# on its own, the exec-form ENTRYPOINT keeps `java` as PID 1 so it receives
# SIGTERM directly (ERT-1240 widened the window that hook is given), and Cloud
# Run can override the whole string without rebuilding the image.
#
#   MaxRAMPercentage=60   1Gi container -> ~600 MB heap, leaving room for
#                         metaspace, code cache, thread stacks and Netty.
#   MaxDirectMemorySize   Netty otherwise assumes it may allocate direct memory
#                         up to max heap, which the container does not have. This
#                         is the usual cause of a container OOM kill with a
#                         perfectly healthy-looking heap.
#   MaxMetaspaceSize      turns runaway metaspace into a Java OOM with a stack
#                         trace rather than a silent kill.
#   UseSerialGC           correct for 1 vCPU, and what the JVM would pick anyway.
#                         Stated so that moving to --cpu 2 is a decision (switch
#                         to G1), not a surprise.
#   ExitOnOutOfMemoryError die and be replaced rather than thrash.
#
# Deliberately NOT set: HeapDumpOnOutOfMemoryError. /tmp on Cloud Run is a tmpfs
# charged against the memory limit, so dumping on OOM consumes the memory you
# just ran out of.
#
# logback-gcp.xml (ERT-1250) ships inside the jar at BOOT-INF/classes, so this is
# a classpath-relative name and no COPY is needed for it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Dlogback.configurationFile=logback-gcp.xml"

# No HEALTHCHECK: Cloud Run ignores it, this JRE image has no curl, and compose
# takes its health gating from Postgres instead.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
