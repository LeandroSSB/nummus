# M17 — Refunds Design

**Goal:** Merchants refund settled payments — full or partial, one or many, never
beyond the original gross. A refund holds the merchant's funds in the journal at
request time, instructs the simulated network to return the money to the payer,
and settles lazily on read. Settlement fees are retained.

**Spec status:** approved design — dimensions validated with the project owner
(boundary via simulator, total + partials, fee retained). Lifecycle mirrors the
M16 payout shape by construction (async boundary forces the two-phase hold).

## Scope

1. **Refunds close the payment cycle.** Money-in settles, money-out pays, and
   now settled money returns: one or more refunds per `SETTLED` intent whose
   sum never exceeds the intent's gross amount.
2. **The hold is accounting, not a column.** Request posts `DEBIT merchant
   ledger account → CREDIT RefundReservedAccount` (a new pooled LIABILITY,
   sibling of the payout reserve — separate so statements read legibly by
   flow). Execution posts `DEBIT refund-reserved → CREDIT PaymentClearing`:
   the money physically returns through the network. Failure/expiry posts the
   compensating return `DEBIT refund-reserved → CREDIT merchant`.
3. **Fees are retained, always.** The payer receives the refund's full value;
   the merchant is debited the same full value; revenue never moves. A refund
   of a fully-refunded intent therefore costs the merchant exactly the
   settlement fee — deliberate, documented, no proportional retrofit.
4. **Two race guards, one documented lock order.**
   - *Refundable balance:* `Σ(refunds of intent in REQUESTED or SETTLED) ≤
     amount`. The request transaction row-locks the intent (`select ... for
     update` on `payments.payment_intent`) BEFORE computing the remaining
     refundable value.
   - *Funds for the hold:* `available ≥ refund value` under the SAME
     ledger-account row lock the payout request uses.
   - Lock order is **intent row → ledger account row**, taken in that order by
     every writer; the payout path takes only the ledger row, so no cycle
     exists. Deadlock-freedom by construction is part of the contract tests.
5. **The M16 stranding lesson applied up front.** The `OutstandingPayouts`
   port generalizes to `OutstandingHolds.anyPending(account)` — freeze/close
   reject (409) while the account has ANY in-flight hold (REQUESTED payout OR
   refund). Every hold keeps exactly one exit under every account state.

## Non-goals (bounds, deliberate)

- **No refund lines in conciliation settlement reports** — as with payouts;
  external statements cover charges only.
- **No refund time window** — a settled intent stays refundable indefinitely
  (until fully refunded); real-world windows wait for a product need.
- **Fee is never refunded or proportionally refunded** — retained in full.
- **Only `SETTLED` intents refund** — CREATED/FAILED/EXPIRED intents reject
  with 409; their money never moved.
- **No listing beyond by-id** — the intent's `refundedTotal` and the derived
  merchant statement are the refund history.
- **BRL only, single currency.**

## Accounting model

| Phase | Postings (one balanced transaction each) |
| --- | --- |
| Request (`REQUESTED`) | `DEBIT merchant ledger account → CREDIT RefundReservedAccount` |
| Execution (`SETTLED`) | `DEBIT RefundReservedAccount → CREDIT PaymentClearingAccount` |
| Network failure (`FAILED`) | `DEBIT RefundReservedAccount → CREDIT merchant ledger account` |
| Expiry (`EXPIRED`) | same compensating return as `FAILED` |

References: `refund <refundId> request|execute|return`. Zero-amount legs are
never posted (house idiom; refund values are positive by validation).

**Refundable balance.** `remaining(intent) = amount − Σ(refund values where
status ∈ {REQUESTED, SETTLED})`. A request must satisfy `0 < value ≤
remaining` AND `available ≥ value` — both checked under the two locks above,
before any side effect (no refund row, no network refund, no journal row on
rejection).

## Lifecycle

```
POST /v1/payment-intents/{id}/refunds ──▶ REQUESTED (hold posted, network refund PENDING)
        GET /v1/refunds/{id} (lazy, mirrors payouts)
   refund SUCCEEDED ─ refund FAILED ─ expiresAt passed
        SETTLED          FAILED            EXPIRED
   (hold → clearing)  (hold → merchant)  (hold → merchant)
```

States and won-transition guards mirror the payout implementation exactly:
post legs first, guarded mark second (`where status = 'REQUESTED'`), throw on
loss so the posting rolls back; events publish only after a won guard;
terminal states are stable; post-expiry network success cannot resurrect an
EXPIRED refund. TTL window and defaults mirror intents/payouts (60–86400s,
default 1800).

## Network refund contract (`psp-simulator`)

- New table `psp_simulator.charge_refund`: id, public_id, charge_public_id
  (references charge), amount (`> 0`), status `PENDING | SUCCEEDED | FAILED`,
  created_at, updated_at — the transfer table's mirror scoped to a charge.
- The network enforces its own invariant: `Σ(charge_refund amounts) ≤
  charge.amount` at creation (PENDING rows count) — the same invariant the
  payments module enforces against the intent; both sides guard, like the
  amount echo check on transfers.
- `PaymentNetwork` gains `createChargeRefund(UUID chargePublicId, Money
  amount)` → `NetworkRefund` and `getChargeRefund(UUID refundPublicId)`
  (reusing `ChargeStatus`). Simulator REST: `GET /simulator/refunds/{id}`,
  `POST /simulator/refunds/{id}/pay`, `POST /simulator/refunds/{id}/fail` —
  mirroring the transfer routes; terminal re-action → 409; unknown → 404.

## REST surface

- `POST /v1/payment-intents/{id}/refunds` — merchant-authenticated,
  tenant-scoped, `Idempotency-Key` required. Body: `amount` (positive,
  ≤ refundable), `expiresInSeconds` (optional, 60–86400). Responses: `201`
  refund view (`refundId`, intentId, amount, status, `expiresAt`, hold
  transaction id); `404` unknown/foreign intent; `409` intent not SETTLED;
  `400` shape violations; `422` value exceeds remaining refundable OR
  insufficient available funds.
- `GET /v1/refunds/{id}` — lazy terminal transition; `404` unknown/foreign.
- `IntentResponse` gains `refundedTotal` (Σ REQUESTED+SETTLED refund values;
  BigDecimal; `0` when none) — the quote-style read the intent surface
  already uses for fees.

## Events

`refund.settled`, `refund.failed`, `refund.expired` (`RefundEventTypes` +
`ALL`) via the transactional outbox, `OutboxRefundLifecycleEvents` mirroring
the payout adapter (envelope: refundId, intentId, chargeRefundId, amount,
currency, status, settledAt, journalTransactionId). The merchant webhook
catalog becomes the union of intent + payout + refund types
(`MERCHANT_EVENT_TYPES`, single-sourced).

## Schema (V19 — one migration)

`V19__refunds_schema.sql`:
1. `payments.refund` — intent-scoped sibling of `payments.payout`: public_id
   unique, intent_public_id (uuid not null), amount `numeric(19,4) > 0`,
   status `REQUESTED|SETTLED|FAILED|EXPIRED`, network_refund_public_id uuid
   not null unique, expires_at, created_at, settled_at, hold/execute/return
   transaction ids (hold not null; execute/return unique). Index on
   (intent_public_id, id desc). Column-scoped update grant on the guarded
   columns only; sequence grants per the V5 precedent.
2. `psp_simulator.charge_refund` — as above, with its own grants.
3. Ledger seed: `('5f9c3b2e-0000-4000-8000-000000000004', 'refund reserve',
   'LIABILITY')`.
4. No merchants change (fees untouched — retained).

The `OutstandingPayouts` port rename to `OutstandingHolds` is code-only (the
port is an interface; the implementing bean's query widens to also count
REQUESTED refunds on the account — refunds join payouts through the intent's
account, resolved in SQL).

## Module placement

All in **`payments`** (owns intents, payouts, the network port): domain
(`Refund`, `RefundStatus`, `CreateRefundCommand`, exceptions), application
(`RefundsService`+Impl, `RefundEventTypes`, `RefundLifecycleEvent(s)`,
`RefundReservedAccount`), interfaces (`RefundsController` + DTOs). Simulator
extends within its schema. The accounts module's guard port generalizes in
place. ArchUnit's existing rules bound everything; no new module.

## Testing strategy

TDD per plan task, remote CI only (megalan), house idioms, sweeps carried
with every copied recipe. Coverage:

- Schema pin (insert-only, defaults, grants, checks).
- Network refunds: lifecycle, terminal re-action 409, over-refund rejection
  at the network (Σ > charge amount), port exposure.
- Request path: hold legs exact; refundable-balance rejection (no side
  effects, all three); funds rejection; **concurrent pins** — N threads
  refunding the same remaining value → exactly one lands (intent-row lock,
  counterfactual-proven like M16); concurrent refunds + payout on the same
  account → no overdraft (ledger-row lock, shared with payouts).
- Full lifecycle: settle/failed/expired legs + events (payload pinned,
  single-row); post-expiry immunity; terminal stability; ownership 404s.
- `refundedTotal` on the intent (partial sums, excludes FAILED/EXPIRED).
- REST matrix: 201/400/404/409/422, idempotent replay, gating 401.
- Freeze/close with an in-flight refund → 409, account stays ACTIVE; after
  the refund terminates → freeze succeeds. Unfreeze unguarded.
- **Design walkthroughs (M16 lesson, applied in spec):** exit completeness —
  every REQUESTED refund reaches a terminal state under every account status
  (ACTIVE throughout by the freeze/close guard; FROZEN/CLOSED impossible
  while pending); per-leg destination — no leg can move any balance anywhere
  an explicit decision does not allow (merchant never negative: hold checked
  under lock; reserve never stranded: single exit).

## Migration / rollout notes

- V19 is additive everywhere; no data backfill; `refundedTotal` derives at
  read time (no denormalized column).
- The refund reserve is created empty by the migration.
- Pre-M17 intents are refundable (any SETTLED intent qualifies); the
  refundable balance starts at the full gross.
