# M16 — Payouts (money-out) Design

**Goal:** Merchants withdraw settled funds. A payout reserves the amount in the
journal at request time, crosses the external boundary as a transfer instruction
processed by the `psp-simulator` (the same `PaymentNetwork` port charges use),
and posts its final legs when the network executes it. Fees net into the
revenue account exactly as settlement fees do.

**Spec status:** approved design — dimensions validated with the project owner
(boundary via simulator, two-phase journal reservation, charge-mirror lifecycle,
opaque destination descriptor, fixed payout fee in the schedule).

## Scope

1. **Money-out closes the product loop.** Money-in exists end to end (intent →
   simulated charge → settlement → derived balance). M16 adds the out direction:
   a merchant requests a payout from a payment account; funds leave through the
   external network to a destination descriptor.
2. **The reservation is accounting, not a column.** At request time the service
   posts `DEBIT merchant ledger account → CREDIT PayoutReservedAccount`. The
   merchant's derived available balance drops the instant the reservation posts;
   no mutable "held balance" exists anywhere.
3. **Lifecycle mirrors the intent/charge pair.** `REQUESTED → SETTLED |
   FAILED | EXPIRED`, driven lazily on read, exactly as intents are: the payout
   GET reads the network transfer; `SUCCEEDED` posts the final legs,
   `FAILED` posts the compensating reversal, past the expiry instant the
   reservation is returned. Won-transition guards publish one event per
   terminal state.
4. **The boundary stays contract-faithful.** `PaymentNetwork` gains two
   methods — `createPayoutTransfer(Money amount, String destinationBankKey)`
   and `getPayoutTransfer(UUID transferPublicId)`. The simulator implements
   them; production code never mocks the boundary. A real PSP adapter would
   carry both directions on one integration, so one port, one adapter.
5. **Fees ride the M9/M14 seams.** `FeeSchedule` gains `payoutFixedAmount`
   (default zero). On execution, the fee posts `DEBIT merchant → CREDIT
   FeeRevenueAccount` alongside the outbound legs; schedule changes keep their
   M14 attributed append-only history (the history row carries the new field;
   pre-M16 rows read zero).

## Non-goals (bounds, deliberate)

- **No payout lines in conciliation settlement reports** — external statements
  cover charges only in M16; payout reconciliation waits until the product
  needs it.
- **No bank-account registry** — the destination is a validated opaque
  descriptor (bank key) on the request, echoed by the network and the journal
  reference; registration/verification of payee accounts is a future milestone.
- **No operator surface** — payouts are merchant self-serve, attributed by
  tenancy; they are not operator writes and never enter the M15 audit log.
- **No payout listing beyond by-id** — the merchant statement (derived journal)
  already answers "where did my money go".
- **BRL only, single currency** — as everywhere else.

## Accounting model

Singleton ledger accounts join the existing family (`PaymentClearingAccount`,
`FeeRevenueAccount`): **`PayoutReservedAccount`**, fixed public id, created by
V18, owned by the payments module's application layer like its siblings.

| Phase | Postings (all one balanced transaction each) |
| --- | --- |
| Request (`REQUESTED`) | `DEBIT merchant ledger account → CREDIT PayoutReservedAccount` |
| Execution (`SETTLED`) | `DEBIT PayoutReservedAccount → CREDIT PaymentClearingAccount` |
| Execution with fee | execution legs **plus** `DEBIT merchant ledger account → CREDIT FeeRevenueAccount` (fee leg omitted when zero, as settlement omits a zero merchant leg) |
| Network failure (`FAILED`) | `DEBIT PayoutReservedAccount → CREDIT merchant ledger account` (compensating return) |
| Expiry (`EXPIRED`) | same compensating return as `FAILED` |

**Overdraft protection.** Check-then-post races: two concurrent payout
requests can both read the same derived balance. The request transaction takes
a row lock on the merchant's ledger account (`select ... for update` via a
`LedgerRepository` lock helper), re-derives the available balance under the
lock, and only then posts the reservation. Insufficient funds under the lock →
`InsufficientFundsException` → 422. The reservation posting itself then makes
every later request see the reduced balance — no double-spend window.

**Transaction references.** Each journal transaction carries a deterministic
reference (`payout <publicId> request`, `... execute`, `... return`), mirroring
`settlement <intentId>`, so statements read chronologically.

## Lifecycle

```
POST /v1/payouts ──▶ REQUESTED  (reservation posted, transfer PENDING on the network)
                        │
        GET /v1/payouts/{id} (lazy, mirrors intent reads)
                        │
        ┌───────────────┼─────────────────────┐
   transfer SUCCEEDED  transfer FAILED   expiresAt passed
        │                │                   │
     SETTLED          FAILED              EXPIRED
  (final legs +      (return +          (return +
   payout.settled)    payout.failed)     payout.expired)
```

- **States:** `REQUESTED → SETTLED | FAILED | EXPIRED` (terminal states never
  transition again; won-transition guard returns the loser's read silently —
  the intent pattern).
- **Expiry is lazy on read**, plus the same instant-granularity rejection M11
  gave intents (an `expiresIn` at or below zero never reaches the journal).
- **Events** (transactional outbox, merchant audience): `payout.settled`,
  `payout.failed`, `payout.expired` — joining `IntentEventTypes` in the
  merchant endpoint catalog.

## Network transfer contract (`psp-simulator`)

- New simulator table `psp_simulator.payout_transfer`: public id, amount,
  destination bank key, status `PENDING | SUCCEEDED | FAILED`, timestamps —
  the charge table's mirror.
- `createPayoutTransfer` inserts `PENDING`; the simulator's REST surface gains
  confirm/fail routes for transfers exactly as it has for charges; settlement
  reports never read transfers (bound above).
- Destination bank key: opaque string, shape-validated at the REST edge
  (non-blank, ≤ 64 chars, `[A-Za-z0-9._-]+` — the label-family idiom), stored
  verbatim, echoed in the payout response and the simulator transfer.

## REST surface

- `POST /v1/payouts` — merchant-authenticated, tenant-scoped, `Idempotency-Key`
  required (stored response replay on retry, M4 semantics). Body:
  `accountPublicId`, `amount` (positive, BRL), `destinationBankKey`,
  `expiresIn` (optional seconds; default mirrors the intent default window).
  Responses: `201` with the payout view (`payoutId`, account, amount,
  destination, status, `expiresAt`, reservation transaction id); `404` unknown
  account (tenant-scoped), `409` account not ACTIVE, `400` non-positive amount
  or malformed destination key (request shape), `422` insufficient funds
  (domain state under the lock).
- `GET /v1/payouts/{id}` — merchant-authenticated, tenant-scoped, lazy
  terminal transition (the read that observes the network state posts the legs
  and publishes the event), `404` unknown.

## Schema (V18 — one migration)

`V18__payouts_schema.sql` adds:

1. `payments.payout` — public id (uuid, default gen), account_public_id,
   merchant-scoped lookup columns, amount (numeric as the ledger stores
   money), destination_bank_key, status, expires_at, transfer_public_id,
   request/execute/return journal transaction ids (nullable except request),
   created_at. Unique index on public_id; index on (account_public_id, id
   desc) for account-scoped reads.
2. `psp_simulator.payout_transfer` — as above.
3. `merchants.merchant` gains `payout_fee_fixed_amount` (numeric not null
   default 0); `merchants.fee_history` gains the same column (default 0 for
   pre-M16 rows). `FeeSchedule` extends to
   `record FeeSchedule(BigDecimal rate, BigDecimal fixedAmount, BigDecimal
   payoutFixedAmount)` with the same validation family (non-negative); `ZERO`
   and every constructor call site update; the M14 fee PUT validates, stores,
   and attributes the new field verbatim in history.

## Module placement

Everything lives in **`payments`** (it owns money-movement and the
`PaymentNetwork` port): domain (`Payout`, `PayoutStatus`,
`CreatePayoutCommand`, exceptions), application (`PayoutsService` +
`PayoutsServiceImpl`, `NetworkTransfer`, `PayoutEventTypes`,
`PayoutReservedAccount`), interfaces (`PayoutsController` + DTOs). ArchUnit's
payments rules already bound the module; no new module, no cross-module table
access. The simulator module extends within its own schema.

## Testing strategy

TDD per plan task, remote CI only (megalan container), delta counting on the
shared container, `ApiDrivers` fixtures — the standing idioms. Coverage:

- Schema pin: insert-only payout rows, tenant scoping, defaults.
- Reservation: posting moves derived balances exactly; concurrent-request pin
  (two requests, one 422 — the lock is the mechanism under test).
- Full lifecycle end to end against the simulator: request → confirm →
  SETTLED legs + `payout.settled`; fail route → return legs +
  `payout.failed`; expiry → return legs + `payout.expired`; won-transition
  (double read publishes one event).
- Fees: nonzero `payoutFixedAmount` posts the revenue leg and reduces the
  merchant net; zero fee omits the leg; fee PUT history carries the field
  (M14 attribution intact).
- REST: idempotent replay, 404/409/422 mapping, tenant scoping, rate limits
  untouched (existing filter).

## Migration / rollout notes

- V18 is additive everywhere (new tables, defaulted columns) — no data
  backfill, no rewrite; pre-M16 fee history reads `payout_fee_fixed_amount = 0`.
- The reservation account is created empty by the migration; an empty reserved
  account simply means no outstanding payouts.
