# M27 — Internal transfers (intra-merchant account-to-account)

## Problem

A merchant with several payment accounts (one per storefront, say) cannot
move booked funds between them: today the only path out of an account is a
payout to the external network. Real payment providers offer internal
book transfers — instant, free, and never leaving the ledger.

## Goals

- `POST /v1/transfers` — merchant-scoped, `@Idempotent`:
  `{fromAccountId, toAccountId, amount}` → `201` with the transfer resource
  (`GET /v1/transfers/{id}`, and listed by `GET /v1/transfers` with the M22
  keyset contract).
- Semantics: both accounts must belong to the authenticated merchant and be
  `ACTIVE`; `fromAccountId != toAccountId`; the transfer executes
  synchronously as ONE balanced journal entry — debit the from-account,
  credit the to-account — committed atomically with the transfer row. There
  is no lifecycle: a transfer exists in exactly one state, `COMPLETED`.
- Sufficiency: the from-account's booked balance must cover the amount,
  checked under the from-account's ledger row lock (the payout reservation
  precedent — `ledger.lockAccount` — minus the network legs).
- Limits deliberately do NOT apply (documented decision): an intra-merchant
  transfer moves money between the merchant's own accounts — merchant-level
  exposure is unchanged, so neither `maxPayoutAmount` nor the daily intent
  volume govern it. Account-level sufficiency is the bound.
- Webhook event `transfer.completed` (the endpoints catalog derives from the
  module's event-type set); counter `nummus.transfers{outcome=completed}`.
- Fee: none (internal movement); BRL only.

## Non-goals

- Cross-merchant transfers (that is settlement/product territory — out of
  scope until a real requirement exists).
- Scheduled/recurring transfers, transfer reversal (a mistaken transfer is
  corrected by transferring back).
- Caps or velocity on transfers (per the decision above).

## Approaches considered

1. **One synchronous balanced posting, no lifecycle (chosen).** The journal
   is the source of truth; a transfer is a fact, not a process. Simplest
   thing that is still a first-class REST resource with idempotency and
   events.
2. A two-phase REQUESTED→COMPLETED lifecycle mirroring payouts. Pointless
   without an external network to await. Rejected.
3. Exposing it as a ledger-level primitive without a REST resource. No
   merchant story (no idempotency, no event, no listing). Rejected.

## Design

### Module placement

The payments module owns transfers (it owns money movement and the
`payments` schema); storage is a new table `payments.transfer` (V27):
`id bigint identity`, `public_id uuid unique`, `merchant_public_id uuid`
(denormalized like the webhook tables — merchant scoping without the
accounts seam on the listing), `from_account_public_id`,
`to_account_public_id`, `amount numeric(19,4) check (> 0)`,
`journal_transaction_public_id uuid`,
`created_at timestamptz` — index `(merchant_public_id, id desc)` for the
listing; `status` column omitted deliberately (single-state resource; the
journal link is the record).

### Service

`TransfersService.create(merchant, cmd)` → `@Transactional`:
1. Resolve both accounts via `accounts.get(merchant, id)` (ownership masked
   as 404; both must be ACTIVE — the freeze guard applies to internal moves
   too).
2. Reject `from == to` (400/422 per the validation family).
3. `ledger.lockAccount(from.ledgerAccountPublicId())`; sufficiency check
   against the from-account's booked balance (the payout precedent's
   `accounts.balance` under the lock) → `InsufficientFundsException`.
4. `ledger.post` the balanced entry (`"transfer <id>"` memo: debit
   from-ledger-account, credit to-ledger-account).
5. Insert the transfer row carrying the journal transaction id; publish
   `transfer.completed`; count. `InsufficientFunds` semantics unchanged.

### REST

`TransfersController` — `@RequestMapping("/v1/transfers")`:
`POST` (idempotent, `@Valid` body: positive amount, `@Digits(15,4)`,
not-null account ids), `GET /{id}`, `GET` (keyset listing, `Next-Cursor`,
`limit` 1–100 — the deliveries contract verbatim; no filters in v1).

### Events and metrics

`TransferEventTypes.COMPLETED = "transfer.completed"` in
`payments.application` (the catalog picks it up); `nummus.transfers`
counter with `outcome=completed` beside the publish.

## Testing

- REST: create → 201, both balances move by exactly the amount in opposite
  directions (the M21 composition reads), statement lines carry the memo;
  GET by id; listing keyset-walks newest-first; foreign transfer id → 404.
- Idempotent replay (same key → stored 201).
- Same-account → 400/422; unknown account → 404; frozen from-account → 409;
  insufficient → 422 (the exception family); amount 0/negative → 400.
- Event deliverability probe (the M25 idiom); counter pin via registry.
- Cross-merchant: merchant B's account as target → 404 for A.

## Migration

`V27__internal_transfers.sql` — the `payments.transfer` table + index;
`V26` remains otherwise the head.
