# M25 — Merchant-facing intent void

## Problem

A merchant who creates a payment intent (a dynamic charge) and no longer wants
it — wrong amount, abandoned checkout — can only wait for expiry. Expiry is
lazy-on-read, publishes `payment_intent.expired`, and abandons the pending
network charge in place (the M18 decision: money-in has no expiry
abandonment on the network). The merchant cannot act, and the intent's story
conflates "timed out" with "withdrawn". M18 explicitly deferred this surface:
"a merchant-facing void is future product work".

## Goals

- `POST /v1/payment-intents/{id}/void` — merchant-scoped, `@Idempotent`,
  ownership masked as 404 for foreign intents (the `payments.get` precedent).
- A distinct terminal state `VOIDED` (not a reuse of `EXPIRED`): merchant
  action is a different fact than time-based expiry — different webhook
  semantics, distinguishable in listings and filters.
- Void is only legal while the intent is `CREATED`. Any terminal state
  (including `EXPIRED` — the lazy expiry on the ownership read wins first)
  rejects with a conflict problem (409) carrying the current state.
- The pay-vs-void race follows the refund-resolver precedent: attempt the
  network cancel, then branch on the post-attempt state —
  - charge was `PENDING` → cancelled: guarded transition `CREATED → VOIDED`,
    publish `payment_intent.voided`, count `nummus.intents{outcome=voided}`;
  - charge `SUCCEEDED` in the race window: money already paid wins — the
    intent settles through the existing path, and the void rejects with the
    settled state;
  - charge `FAILED`: the intent fails through the existing path, void rejects.
- Network port grows `cancelCharge(UUID)` with post-attempt semantics (the
  `cancelChargeRefund` adapter pattern: catch the not-pending rejection,
  re-read). The simulator grows `POST /simulator/charges/{id}/cancel`
  (`PENDING → CANCELLED`).
- Webhook event `payment_intent.voided` (the endpoint catalog derives from
  `IntentEventTypes.ALL` automatically).
- No ledger postings anywhere on the void path — a pending charge has no
  journal existence; nothing to reverse.

## Non-goals

- Voiding payouts or refunds (money-out already has cancel/return paths).
- Scheduled/background expiry (stays lazy-on-read).
- Operator-initiated void.
- Changing expiry semantics or the expiry-vs-settle ordering.

## Approaches considered

1. **Distinct `VOIDED` state + network cancel with post-attempt branching
   (chosen).** Honest state machine; the race is resolved the way refund
   resolution already resolves it. Two constraint swaps in one migration.
2. Reuse `EXPIRED` with a cause flag. Conflates two facts; webhook consumers
   could not distinguish; cheaper migration but worse contract. Rejected.
3. Void as a client-side convention (short TTL + ignore). Not a product
   surface; the charge stays payable on the network — the exact hazard void
   exists to remove. Rejected.

## Design

### Migration (`V25__intent_void.sql`)

Two check-constraint swaps (the V20 drop/add pattern):
- `payments.payment_intent.status` gains `VOIDED`.
- `psp_simulator.charge.status` gains `CANCELLED`.
No new tables, no grants to change (the app role already updates
`payment_intent.status`; simulator routes are unauthenticated).

### Port and simulator

- `PaymentNetwork.cancelCharge(UUID chargePublicId)` — returns the
  post-attempt charge; the adapter mirrors `cancelChargeRefund`'s
  catch-and-reread.
- `SimulatorService.cancel(UUID chargePublicId)` — guarded
  `PENDING → CANCELLED`, throwing the not-pending shape the adapter expects;
  `POST /simulator/charges/{id}/cancel` beside `pay`/`fail`.

### Service

`PaymentsServiceImpl.voidIntent(merchantPublicId, publicId)`:
1. `payments.get`-style ownership read (masking foreign as unknown) — the
   read's lazy expiry settles the expiry question first.
2. If status is not `CREATED` → `IntentNotVoidableException(publicId,
   status)` → 409.
3. `var charge = network.cancelCharge(intent.chargePublicId())` — one
   attempt, post-attempt state.
4. Branch on `charge.status()`: `CANCELLED` → guarded
   `transitionToVoided` (won-transition: publish `voided`, count) and return
   the voided intent; `SUCCEEDED` → run the existing `settle` path and throw
   `IntentNotVoidableException(publicId, SETTLED)`; `FAILED` → the existing
   failure transition, then the same rejection.
5. Racing losers (another reader settled/failed/expired between steps) follow
   the existing silent-reread convention.

### Controller

`@Idempotent @PostMapping("/{id}/void")` on `PaymentsController` returning
`200` with the `IntentResponse` (the by-id shape) — void completes
synchronously; retries replay via the stored response.

### Testing

- REST: void a CREATED intent → 200, status `VOIDED`, `payment_intent.voided`
  event deliverable; listing shows `VOIDED`; charge on the simulator reads
  `CANCELLED`; a later `pay` on the cancelled charge is rejected by the
  simulator.
- Races: pay the charge (simulator) immediately before the void call → void
  rejects 409 with settled state, intent is SETTLED and settled money is
  intact; fail the charge before void → 409, intent FAILED.
- Terminal rejects: void a SETTLED / EXPIRED (backdated) / already-VOIDED
  intent → 409.
- Foreign intent → 404; idempotent retry replays.
- Counter `nummus.intents{outcome=voided}` pins via registry.
- Simulator: cancel is PENDING-only.

## Migration

`V25__intent_void.sql` only (constraint swaps); `V24` remains otherwise the
head of schema history.
