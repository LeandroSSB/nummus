# M21 — Balance composition (derived pending/reserved view)

## Problem

`GET /v1/accounts/{id}/balance` answers "how much does the account hold" with a
single flat number: the ledger-derived sum of everything booked on the merchant's
account. That number is correct, but it is not legible:

- Money inbound — charges created or paid but not yet settled — is invisible.
  It sits in the clearing account (or has not been paid yet) and never touches
  the merchant's ledger account until settlement.
- Money outbound — REQUESTED payouts and refunds — is already deducted from the
  balance (reservations are real postings into reserved accounts), so a merchant
  who requested a payout sees their balance drop with no way to distinguish
  "spent" from "locked, executing".

A payment account core must answer, in one read: what is booked, what is coming,
and what is locked. Today that takes three mental joins the merchant cannot do.

## Goals

- Expose balance composition on `GET /v1/accounts/{id}/balance`:
  - `balance` — booked funds, exactly as today (ledger-derived, natural sign,
    available funds positive).
  - `pendingIncoming` — gross amount of the account's payment intents that are
    not yet in a terminal state (created…paid, pre-settlement).
  - `reservedOutgoing` — total locked by REQUESTED money-out: payout amounts
    plus refund amounts (exactly the reservation legs' totals; the payout
    execution fee is not part of a reservation).
  - `currency` — as today.
- Same three figures in the statement header
  (`GET /v1/accounts/{id}/statement`) alongside the existing `balance`, so both
  reads carry one mental model.
- Keep payout sufficiency semantics unchanged: what a new payout may reserve is
  `balance` (reservations already deducted). This milestone exposes the
  composition; it does not re-derive availability.

## Non-goals

- No schema change is expected; pending/reserved sums read existing payments
  tables. Index additions only if the queries need them.
- No new webhook events, no balance history or time-travel reads, no
  per-merchant limits (standing backlog threads, separate milestones).
- No change to how reservations post — the ledger stays the sole source of
  truth for booked money.

## Approaches considered

1. **Domain-state derivation through an accounts-owned port (chosen).** The
   payments module owns lifecycle truth (which intents/payouts/refunds are
   pending or REQUESTED); the journal cannot answer "what is coming" at all —
   an unpaid intent has no posting anywhere. Accounts defines a port
   (`MoneyInFlight`) returning the two sums for an account; payments provides
   the adapter, exactly like the existing
   `OutstandingHolds` inversion. Simple reads, boundaries intact.
2. Pure-journal derivation (resolve clearing/reserved postings back to their
   owning account via recorded transaction ids). Ledger-purist, but blind to
   unpaid intents, so it still needs payments state for part of the answer —
   two derivations for one field. Rejected.
3. Materialized running totals maintained at write time. Violates the locked
   "balances are derived" decision. Rejected.

## Design

### Contract

`GET /v1/accounts/{id}/balance` (merchant-scoped, as today) becomes:

```json
{
  "amount": "120.00",
  "pendingIncoming": "300.00",
  "reservedOutgoing": "50.00",
  "currency": "BRL"
}
```

(The booked figure keeps its shipped field name `amount`; the change is
additive.) `GET /v1/accounts/{id}/statement` gains `pendingIncoming` and
`reservedOutgoing` next to its existing `balance`.

Semantics, stated once in the Javadoc and pinned by tests:

- `pendingIncoming` is informational: not yet in `balance`. When the intent
  settles, `pendingIncoming` drops by the gross amount and `balance` rises by
  the net (gross − fee).
- `reservedOutgoing` is informational: already deducted from `balance`. A
  REQUESTED payout contributes its amount (the reservation debit); when it
  executes, `reservedOutgoing` drops by the amount and `balance` drops by the
  execution fee — the fee leg books at execution, not at reservation. REQUESTED
  refunds contribute their amount and execute with no further balance movement
  (fees are retained, never reversed).
- `available for a new payout == balance` — unchanged behavior, now legible.

### Module boundaries

- `accounts` owns the port and the endpoint; it never depends on payments
  (same seam direction as `OutstandingHolds`).
- `payments` implements the adapter: two queries (pending intents by account;
  REQUESTED payouts/refunds by account) summing existing columns. No
  cross-module table access from accounts.

### Consistency guard

Reservations are real postings, so the payments-side sum and the ledger's
reserved-account balances must agree while states are REQUESTED. An
integration test pins the equivalence (sum of REQUESTED payout+refund
reservations per account == payments-domain sum), so a future bug that books
legs inconsistently fails a test instead of drifting silently.

## Testing

- Unit: port adapter sums (pending/terminal intent split; REQUESTED vs executed
  payout/refund split; fee exclusion for payouts).
- REST integration, one account walked through the full lifecycle: intent
  created → pendingIncoming rises; charge paid → still pendingIncoming; settle
  → balance +net, pendingIncoming −gross; payout requested → balance −amount,
  reservedOutgoing +amount; payout executed → reservedOutgoing −amount,
  balance −fee; refund requested/executed symmetric (no fee movement).
- Statement header carries the same three figures at each step.
- Existing balance/statement tests keep their assertions (additive change).

## Migration

None expected (V23 stays the head). If the adapter queries need an index on
payments tables, add `V24__balance_composition_indexes.sql` in the plan.
