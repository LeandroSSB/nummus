# M26 — Velocity limits (rolling daily intent volume)

## Problem

M23 bounded single operations (`maxIntentAmount`/`maxPayoutAmount`), but
exposure scales with frequency: ten 5 000 BRL intents in an hour each pass
the static cap. Risk control needs a rate dimension — how much a merchant may
ATTEMPT per rolling day — enforced at the same write M23 guards.

## Goals

- One new per-merchant knob, operator-governed through the existing limits
  resource (additive everywhere):
  - `maxDailyIntentVolume` — BRL, nullable (null = unlimited, the default).
- Semantics: the sum of the gross amounts of intents CREATED in the trailing
  24 hours (attempts, any status — attempts are what bound exposure) plus the
  new intent's amount must not exceed the cap. Inclusive: equal passes.
- Enforcement at intent creation, after the static `maxIntentAmount` check
  and before the network charge — an over-velocity request leaves no row.
  Rejections are `422` (`PaymentVelocityExceededException`) carrying the
  window usage, the requested amount, and the cap.
- The operator REST extends: `PUT/GET /v1/merchants/{id}/limits` gain the
  field; history entries and the audit record carry it; validation mirrors
  the static caps (`@DecimalMin` exclusive, `@Digits(15,4)`).
- Storage: `max_daily_intent_volume` column on `merchants.merchant`
  (nullable, `check > 0`), the `payment_limits_entry` history table gains the
  column (nullable — additive, no backfill; null entries mean the knob was
  unset at that change).

## Non-goals

- Payout velocity (money-out reservation already bounds intraday outflow;
  its rate cap is a separate decision).
- Count-based velocity (transactions/day), calendar-day windows, per-account
  granularity, webhook events on limit changes.
- Retrying rejected intents counts toward the window (a created-then-voided
  intent's amount still counts — attempts are attempts; document this).

## Approaches considered

1. **Rolling 24h window over created intents, one knob (chosen).** One
   indexed range query at the guarded write; the standard risk semantics;
   reuses M23's governance end to end.
2. Calendar-day reset windows. Cheaper to reason about for merchants but
   bursty at midnight and needs timezone decisions. Rejected.
3. Token-bucket rate limiter. Smooths bursts but answers a different
   question (arrival rate, not money at risk). Rejected.

## Design

### Enforcement

Advisory semantics (accepted, per the M23 static-cap precedent): the check
reads committed state and precedes the insert, so a burst of concurrent
requests can transiently exceed the cap by roughly (in-flight count − 1) ×
amounts. The bound is governance, not an accounting invariant; serialization
(per-merchant advisory lock) is the upgrade path if a hard bound is ever
required.

`PaymentsServiceImpl.create`, directly after the static cap check:

```java
var daily = limits.maxDailyIntentVolume();
if (daily != null) {
  var accountIds = accounts.listPublicIds(merchantPublicId);
  var windowUsage = repository.createdVolumeSince(accountIds,
      Instant.now().minus(Duration.ofHours(24)));
  if (windowUsage.add(cmd.amount()).compareTo(daily) > 0) {
    throw new PaymentVelocityExceededException(merchantPublicId, windowUsage,
        cmd.amount(), daily);
  }
}
```

`createdVolumeSince(List<UUID> accountPublicIds, Instant from)` lives in
`PaymentsRepository`, summing `amount` where `account_public_id in (:ids)`
and `created_at >= from` — the scoping seam is `AccountsService.listPublicIds`
(same as the listings; no cross-module SQL). `windowUsage` is `Money`
(payments wraps the BigDecimal sum in the command's currency).
Unsettled/voided/expired attempts all count (documented above).

### Storage (`V26__velocity_limits.sql`)

- `alter table merchants.merchant add column max_daily_intent_volume
  numeric(19,4) check (max_daily_intent_volume > 0)`.
- `alter table merchants.payment_limits_entry add column
  max_daily_intent_volume numeric(19,4)`.
- Index for the window query: `create index payment_intent_created_at_idx on
  payments.payment_intent (created_at)` — scoping rides the existing
  account index after the ids filter (verify against the planner cost in
  review; a composite is unnecessary at this scale).

### Types

- `PaymentLimits` gains `BigDecimal maxDailyIntentVolume` (third nullable
  component; `unlimited()` passes null) — the merchants module stays on raw
  BigDecimal ([[merchants-module-no-money]]).
- DTOs extend additively (`UpdateLimitsRequest`, `LimitsResponse`,
  `LimitsHistoryResponse`, `PaymentLimitsEntry`).

## Testing

- REST enforcement: cap 1 000; create intents summing 990 → next 20 intent
  rejects 422 (detail names the window usage 990, requested 20, cap 1 000);
  intent 10 passes (inclusive at exactly 1 000).
- Window rolls: backdate the earliest intent's `created_at` past 24h
  (adminConnection UPDATE, the suite idiom) → its amount frees up.
- Voided/expired attempts still count (create, void, assert the volume
  unchanged — the listing/balance probes or a direct follow-up rejection).
- Unset = unlimited (large intent passes).
- Operator surface round-trip: PUT with the field, GET returns it, history
  entry carries it with attribution; null-clearing PUT resets to unlimited.
- Static + velocity compose (over static rejects before velocity; both set,
  request passes static, exceeds velocity → 422 velocity).

## Migration

`V26__velocity_limits.sql` — the two column additions + the created_at
index; `V25` remains otherwise the head.
