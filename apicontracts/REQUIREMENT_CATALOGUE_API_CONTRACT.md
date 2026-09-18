# Requirement Catalogue API Contract

> **Status:** LIVE
> **Last Updated:** 2026-09-18 (ERT-1145, first issue)

`GET /api/requirement-templates` — the catalogue of document types a new hire can be asked for.
Read-only; there is no write endpoint in Phase 1, because the catalogue is **seeded data** and editing
it is an admin screen that does not exist yet.

The catalogue is what the add-hire screen shows HR, and its order is the order a new hire reads their
checklist in on a phone.

Read [the conventions](README.md) first.

---

## Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/requirement-templates` | bearer | The active document types, in `sort_order` |

---

## The shape, and why

**The catalogue is read once, at hire creation, and copied onto the employee.** Nothing downstream
consults it again for a hire already collecting. That snapshot is what stops a template edit from
moving somebody's progress under them, or from making a completed hire retroactively incomplete.

So this endpoint is **not** how a client learns what a given hire owes. It is the catalogue as it
stands today, for the add-hire form. A hire's own requirement set comes back with that hire, from its
own snapshot, and the two can legitimately differ.

**Four domain fields are withheld, and the omissions are the control.** `isActive` is always `true` on
this route, and publishing a constant invites a client to filter on it and then to ask for the
inactive ones. `expires`, `validityMonths` and `renewalLeadDays` are Phase 4 validity-window internals
that a later ticket will change and nothing on the add-hire screen reads. A serialised domain model
would publish whatever it happens to carry; a hand-written wire type per surface is what makes the
boundary a decision rather than an accident.

---

## `GET /api/requirement-templates`

**Roles:** any authenticated user not owing a password change — both `HR_OFFICER` and `HR_ADMIN`.

No query parameters. The whole active catalogue comes back in one response; there is no filtering by
employment type here, deliberately — the method that does that is reserved for hire creation, and
borrowing it would blur the one call the snapshot rule depends on.

```bash
curl -s http://localhost:8080/api/requirement-templates -H "Authorization: Bearer $TOKEN"
```

### Response — `200`

Abridged to three of the fourteen seeded rows.

```json
{
  "result": "success",
  "data": [
    {
      "id": "c00000000001",
      "name": "Government-issued ID",
      "instructions": "Upload a clear photo or scan of a valid government-issued ID, front and back.",
      "isRequired": true,
      "sortOrder": 1
    },
    {
      "id": "c00000000002",
      "name": "Birth certificate",
      "instructions": "Upload the PSA-issued birth certificate. All corners must be visible and the text legible.",
      "isRequired": true,
      "sortOrder": 2
    },
    {
      "id": "c00000000010",
      "name": "Certificate of employment (previous employer)",
      "instructions": "Required only if you have previous employment. Upload the certificate from your last employer.",
      "isRequired": false,
      "sortOrder": 10
    }
  ],
  "meta": { "total": 14 }
}
```

| Field | Notes |
|-------|-------|
| `id` | 12 characters. **Do not hard-code one** — the catalogue is seeded data and replacing it is a seed change, not a code change |
| `name` | What the checklist row says |
| `instructions` | Shown to the hire; written for a phone screen. May be long — do not truncate without a tooltip |
| `isRequired` | **Optional requirements are excluded from progress arithmetic**, numerator and denominator both. Four of the fourteen seeded rows are optional |
| `sortOrder` | Render in this order. **Do not re-sort by name** — the order is an HR decision, not a consequence of insertion |

**Only active templates are returned.** The handler calls `findAll()`, which carries
`includeInactive = false` as a defaulted parameter — a reader checking the call site alone would
conclude otherwise, which is why it is stated here.

**An empty catalogue is a `200` with an empty list, never a `404`.** Show an empty state.

### Failures

| Status | `code` | Cause |
|--------|--------|-------|
| `401` | — | no token, or an invalid one. Empty body |
| `409` | `password_change_required` | the caller owes a password change |

There is no `404` on this route. There is nothing to not find.

---

## Suggested client flow

This endpoint is one of three independent reads the add-hire form needs; none depends on another, so
issue them in parallel. The sequence is in
[Reference data](REFERENCE_DATA_API_CONTRACT.md#suggested-client-flow).

Bind the picker to `id`, render in `sortOrder`, and group by `isRequired` if the screen distinguishes
mandatory from optional — the flag is the only thing that separates them.

---

## Guarantees

- **Ordering is deterministic**, by `sort_order` then `name`. The name tiebreak is not decoration:
  `sort_order` carries a database default of `0`, so an admin insert that omits it produces ties, and
  ties resolved by whatever the engine returns are a checklist that reorders itself between requests.
- **Inactive templates never reach this route.**
- **The response carries no internal identifier beyond the template id** — no storage key, no
  employment-type mapping, no validity-window field.

## Known limits

- **The catalogue is illustrative, pending Q2.** The fourteen seeded rows are PRD Appendix A and have
  not been ratified by anyone in HR. They will change. A client that hard-codes an id or a name will
  break when they do.
- **There is no create, update or delete.** Phase 2 owns the admin screen (§8.11). Until then,
  changing the catalogue is a migration.
- **No filtering by employment type.** Which subset a given hire receives is decided server-side at
  creation and snapshotted; this route cannot answer it and should not be made to.
- **`meta.total` is the row count, not a page count.** The response is unpaged — fourteen rows today,
  and nothing caps it.
- **The name tiebreak is collation-sensitive**, and H2 in PostgreSQL mode is not PostgreSQL. It can
  only fire on a `sort_order` tie, which the seeded catalogue never produces; a collation decision is
  owed with ERT-1190.
