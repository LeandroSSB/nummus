# M23 — Payment limits (per-merchant caps)

## Problem

Any merchant can create a payment intent or request a payout of any size —
the only amount validation is shape (`> 0`, scale). A payment provider must be
able to bound per-merchant exposure: a compromised merchant key, a buggy
integration, or a risky merchant should not be able to move unbounded amounts
in a single operation. Operators need per-merchant caps they can set, change,
and audit — exactly the governance shape fee schedules already have.

## Goals

- Per-merchant limits, operator-governed:
  - `maxIntentAmount` — ceiling on a single payment intent's amount.
  - `maxPayoutAmount` — ceiling on a single payout's amount.
  - Each nullable; absent means unlimited (the default for existing and new
    merchants — the change is purely additive).
- Enforcement at the money-moving writes, before any network or ledger action:
  - Intent creation rejects amounts above `maxIntentAmount`.
  - Payout creation rejects amounts above `maxPayoutAmount` (checked alongside
    the existing sufficiency check, under the same ledger row lock ordering —
    validation precedes the reservation, like the destination resolution).
- Rejections surface as `422` problem details with a domain exception, the
  same class of failure as `InsufficientFundsException` (semantic rejection of
  a well-formed request).
- Operator surface, mirroring the fee-schedule governance exactly:
  - `PUT /v1/merchants/{id}/limits` — replace the merchant's limits;
    operator-authenticated, idempotent, attributed to the acting operator key.
  - `GET /v1/merchants/{id}/limits` — the current limits.
  - `GET /v1/merchants/{id}/limits-history` — append-only, paginated exactly
    as fee history is, each entry carrying the acting operator key id and
    timestamp.
- Every limit change lands in the operator audit log (the M15 surface) and in
  the limits history table.

## Non-goals

- Velocity/windowed caps (daily volume, count per minute) — time-windowed
  aggregation is its own milestone; static single-operation caps come first.
- Limits on refunds (bounded anyway by the refunded-total cap against the
    settled intent) or on fee schedules.
- Per-account (vs per-merchant) granularity.
- Webhook events for limit changes (operators read the audit log).

## Approaches considered

1. **Separate limits table with append-only history, the fee-schedule pattern
   (chosen).** `merchants.payment_limits` holds the current row per merchant;
   `merchants.payment_limits_history` appends every change with the acting
   operator key. The governance plumbing (operator PUT, attribution, history
   listing) is proven; limits stay a distinct concern from pricing.
2. Columns on the fee-schedule tables. Conflates pricing with risk policy and
   forces fee changes to carry limits. Rejected.
3. Global application properties (max amount for everyone). The backlog
   already records per-merchant override as the missing piece; a global knob
   cannot bound one risky merchant. Rejected.

## Design

### Storage (`V24__payment_limits.sql`)

- `merchants.payment_limits`: one row per merchant (`merchant_public_id`
  unique), `max_intent_amount numeric(19,4) null`, `max_payout_amount
  numeric(19,4) null` (both `check (amount > 0)` when present), `updated_at`.
- `merchants.payment_limits_history`: append-only — `merchant_public_id`,
  both amounts, `acting_operator_key_public_id`, `recorded_at`. Grants mirror
  the fee tables'.

### Module boundaries

- Limits are merchant-scoped reference data: the merchants module owns
  storage, the operator REST surface, and a read port
  (`PaymentLimits find(UUID merchantPublicId)` returning the nullable caps).
  Payments consumes the port at intent and payout creation — the same
  inversion `FeeSchedule`/`findFeeSchedule` and `MoneyInFlight` use
  (`PaymentLimits` record with two nullable `Money` fields; merchants
  implements the lookup).
- The merchants module never inspects payments; payments never queries
  merchants' tables directly.

### Enforcement points

- `PaymentsServiceImpl.create`: after account resolution and before the
  network charge creation, reject `amount > maxIntentAmount` with
  `PaymentLimitExceededException` (new domain exception, merchant, requested,
  cap).
- `PayoutsServiceImpl.create`: after destination resolution and account
  status, before the ledger lock and sufficiency check, reject
  `amount > maxPayoutAmount` the same way — a request over the cap must never
  take the ledger lock or create a network transfer.
- `GlobalExceptionHandler` maps the exception to `422 UNPROCESSABLE_ENTITY`,
  beside `InsufficientFundsException`.

### Operator REST

- `PUT /v1/merchants/{id}/limits` with `{"maxIntentAmount": "5000.0000",
  "maxPayoutAmount": "2000.0000"}` (either field null/absent = unlimited;
  validation mirrors `FeeRequest`'s numeric rules). Writes history + audit,
  returns the stored limits.
- `GET` returns the current limits (nulls surfaced as JSON null — absent
  means unlimited).
- `GET /v1/merchants/{id}/limits-history` cursor-paginates like
  fee-history.

## Testing

- REST: operator sets caps; merchant intent above/below `maxIntentAmount`
  (422 vs 201); payout above/below `maxPayoutAmount` (422 vs reservation);
  unset fields unlimited; invalid bodies rejected; merchant key cannot touch
  the operator endpoints (401/403 per the existing operator gate).
- History: PUT twice — two entries, newest first, attribution carries the
  acting operator key.
- Unit: enforcement ordering (cap rejection precedes network/ledger action —
  assert no charge/transfer row exists after rejection).
- Enforcement equality is inclusive: amount == cap passes.

## Migration

`V24__payment_limits.sql` — the two tables above; no changes to existing
tables.
