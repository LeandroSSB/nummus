# M14 — Audit Attribution

**Date:** 2026-09-22
**Status:** approved design, pending implementation plan

## Goal

Close the M8 and M9 attribution threads together: operator keys gain an identity label (who minted, who acted), and fee-schedule changes become attributed, append-only history — replacing two mutable columns rewritten in place with a queryable trail of who changed what, when. Settlement semantics are untouched; this is observability and accountability, not new payment behavior.

## Product decisions (locked)

1. **Identity is a label on the operator key.** `merchants.operator_key` gains a non-null, immutable `label` (1–64 chars, non-blank), set once at mint; the key is the identity. No operator entity, no RBAC — an entity can group labels later without rewriting history.
2. **Fee history is append-only entries plus a cached current.** Every fee change appends a typed `fee_schedule_entry` (attributed to the acting operator key) and updates the merchant's existing `fee_rate`/`fee_fixed` columns as a cache — one transaction. Zero reader migration; settlement and quote paths read the cache unchanged.
3. **Pre-M14 state attributes to NULL, rendered as `system`.** The backfill's seed entries carry `created_by = NULL` — "no attribution" is the truth for state whose actor predates identity; no fake sentinel actor row.
4. **A no-op fee change still appends** — a deliberate re-assertion is a recorded fact, not noise.
5. **History and labels are operator-only.** Merchant-facing surfaces expose neither.

## Operator identity

**Migration (V16, part one):**

```sql
alter table merchants.operator_key
  add column label text not null default 'system';
```

Existing keys backfill as `system`. Labels are immutable by construction: no route mutates `operator_key.label`.

**Mint surfaces:**
- `POST /v1/operator/api-keys` — request gains a required `label`; blank or longer than 64 characters → 400 problem+json.
- `POST /v1/operator/api-keys/current/rotate` — the new key carries the calling key's label forward (a rotation is the same person, new credential).
- `POST /v1/operator/bootstrap` — the bootstrap request gains a required `label` for the first key.

**Read surfaces:** mint and rotate responses and the operator key listing gain `label`. `AuthenticatedOperator` stays `keyPublicId`-only — attribution records the key id; the label joins at read time and cannot go stale because it is immutable. Merchant API keys are untouched.

## Fee schedule history

**Migration (V16, part two):**

```sql
create table merchants.fee_schedule_entry (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  rate        numeric(9,6) not null,
  fixed       numeric(19,4) not null,
  valid_from  timestamptz not null default now(),
  created_by  uuid references merchants.operator_key(public_id),
  created_at  timestamptz not null default now()
);
create index fee_schedule_entry_merchant_idx
  on merchants.fee_schedule_entry (merchant_id, id desc);

grant select, insert on merchants.fee_schedule_entry to nummus_app;
```

`created_by` is nullable — NULL is the pre-attribution sentinel. Insert-only grants: history is write-once, like conciliation reports.

**Backfill:** one seed entry per existing merchant, values copied from its current columns, `created_by = NULL`, `valid_from = now()` — the trail starts at M14 with the then-current schedule.

**Behavior.** `PUT /v1/merchants/{id}/fee` keeps its exact contract (same validation, same 200, same M9 settle-time semantics) and gains, in the same transaction: an appended `fee_schedule_entry` attributed to the acting operator key's public id, and the `merchant.fee_rate`/`fee_fixed` cache update. The merchant's current columns always equal its latest entry.

**Listing.** `GET /v1/merchants/{id}/fee-history?after=&limit=` — operator-authenticated, newest-first, array body with the `Next-Cursor` response header when more pages exist, `limit` 1–100 (outside → 400), unknown cursor → empty page without a header — the M10 pagination contract verbatim. Each row: `entryId`, `rate`, `fixed`, `validFrom`, `createdBy` (key public id or null), `createdByLabel` (joined; null renders as `"system"`).

## Error handling

- Malformed/blank/oversized/absent `label` on mint, rotate, or bootstrap → 400 problem+json.
- Unknown or foreign merchant on the history route → 404, deliberately indistinguishable.
- `limit`/`after` semantics identical to the deliveries listing.

## Security and invariants

- History is append-only: `nummus_app` holds no update or delete on `fee_schedule_entry`; corrections are new entries.
- The cache pair and the appended entry commit atomically — the cache can never diverge from the latest entry through the service path.
- Labels are immutable; attribution by key id is stable forever.
- Fee history and labels appear only on operator-authenticated surfaces.
- No PII requirement on labels — they are display identifiers, validated only for shape.

## Testing

1. **Schema/roles:** `label` non-null with `system` default; `fee_schedule_entry` columns, nullable FK, indexes; `nummus_app` grants are select+insert only (assert no update/delete); existing merchants carry exactly one backfill seed entry with NULL attribution.
2. **Labels:** operator mint without a label → 400; blank → 400; 65 chars → 400; valid → 201 with label echoed; listing shows labels; rotate carries the label; bootstrap requires one.
3. **History behavior:** a fee PUT appends one entry attributed to the acting key AND updates the cache (asserted together); a second change yields two entries newest-first; a no-op PUT still appends; settle-after-change flows stay green (cache read path).
4. **Listing:** pagination walk with `Next-Cursor`; unknown cursor → empty page; foreign merchant → 404; limit 0/101 → 400; `createdByLabel` joins correctly and renders `system` for NULL.
5. **Operator-only:** the history route 403s for merchant keys (role mismatch, the M8 vocabulary); merchant surfaces never expose labels or history.

Baseline at plan time: 329 tests, all green.

## Non-goals / deliberate bounds

- **No RBAC and no operator entity** — labels are identity-light; grouping and roles can come later without history rewrites.
- **Only fee changes are attributed** — merchant creation, key operations, and conciliation triggers stay unattributed (a general audit log can build on the label seam).
- **PUT retries append per delivery** — a network-level retry of the fee route leaves two identical entries; honest as "applied twice". The `@Idempotent` machinery is POST-scoped and deliberately not extended to PUT here.
- **`system` is a sentinel, not an actor** — pre-M14 attribution is genuinely absent.
- **No label i18n/uniqueness constraints** — labels need not be unique; the key id disambiguates.
