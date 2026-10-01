# M22 — Merchant listings (keyset pagination)

## Problem

A merchant can create and fetch resources by id, but cannot list them: there
is no `GET /v1/payment-intents`, `GET /v1/payouts`, or `GET /v1/refunds`, and
the bank-account registry lists without any pagination. Every real payment API
answers "show me my intents" — today nummus answers only "fetch the one you
already know". The standing backlog thread on cursor pagination for the
bank-account listing rides the same gap.

## Goals

- Merchant-scoped, keyset-paginated listings, following the established
  deliveries-listing pattern exactly (`after` cursor + `limit`, fetch
  `limit+1`, `Next-Cursor` response header when more pages remain):
  - `GET /v1/payment-intents?status=&account=&after=&limit=`
  - `GET /v1/payouts?status=&account=&after=&limit=`
  - `GET /v1/refunds?status=&account=&after=&limit=`
  - `GET /v1/bank-accounts?after=&limit=` (closes the standing thread)
- Ordering: newest first by internal identity (`id desc`), the same
  total-order keyset the webhook deliveries listing uses; the cursor is the
  last item's `public_id`, resolved server-side to the internal id.
- `limit` between 1 and 100, default 50; invalid values are rejected the way
  the deliveries controller rejects them.
- Responses reuse the by-id DTOs (`IntentResponse`, `PayoutResponse`,
  `RefundResponse`, and the bank-account response DTO) as JSON arrays.
- Ownership is enforced in SQL: resources surface only when they resolve to an
  account (or registry entry) owned by the authenticated merchant — another
  merchant's resource is invisible, not 403.

## Non-goals

- Date-range filters, full-text search, sorting options (backlog threads;
  keyset ordering is fixed).
- CSV/export (separate milestone).
- Operator-namespace listings (audit log and operator endpoints already
  paginate).
- Schema changes — all four tables already carry `bigint` identity ids.

## Approaches considered

1. **Keyset by internal identity, deliveries pattern (chosen).** Port
   `listDeliveries` verbatim in shape: `where ... and (:after::uuid is null or
   x.id < (select x2.id from ... where x2.public_id = :after)) order by
   x.id desc limit :limit`. Zero new concepts in the codebase; stable
   pagination under concurrent inserts; the cursor is opaque-ish (a public id).
2. Offset pagination (the statement's `Page(offset, limit)`). Skips pages and
   duplicates rows under concurrent inserts; the codebase already treats
   keyset as the hardened pattern. Rejected.
3. Cursor tokens with embedded position (base64 of sort key). More machinery,
   no user-visible gain over a public-id cursor here. Rejected.

## Design

### Queries

All listings live in the module that owns the table, behind its repository
interface, mirroring `listDeliveries`:

- Intents: `from payments.payment_intent i where i.account_public_id in
  (select a.public_id from accounts.payment_account a where
  a.merchant_public_id = :merchantPublicId)` plus optional `:status` /
  `:account` equality filters, then the keyset clause on `i.id`.
- Payouts: same shape on `payments.payout p` (`p.account_public_id`).
- Refunds: `from payments.refund r join payments.payment_intent i on
  i.public_id = r.intent_public_id` with the same account subselect — the
  join `OutstandingHolds` already uses.
- Bank accounts: `from merchants.bank_account b where
  b.merchant_public_id = :merchantPublicId` plus the keyset clause (the table
  already filters nothing today; the listing gains the cursor and keeps its
  current ordering semantics expressed as `id desc`).

An `after` cursor that does not resolve (deleted resource, or one owned by
another merchant) yields an empty page with no `Next-Cursor` — mirroring the
deliveries listing. Pin in tests.

### Controllers

Each controller gains a `@GetMapping` at the collection path with
`@RequestParam(required = false) String status / UUID account / UUID after`
and `@RequestParam(defaultValue = "50") int limit`; validation and the
`Next-Cursor` header follow the deliveries controller line for line. The
bank-account listing changes its (currently unparameterized) `list()` to the
same shape — additive query parameters, existing callers unaffected.

### Idempotency and auth

Listings are unauthenticated-write-free GETs under the merchant Bearer filter;
no `Idempotency-Key` involvement (reads), rate limits apply as everywhere.

## Testing

- Repository/REST integration per resource: create a merchant with several
  resources across two accounts; paginate with `limit` below the total;
  assert the `Next-Cursor` chain covers every item exactly once, newest
  first; the terminal page carries no header.
- Filters: `status` narrows to that status; `account` narrows to that
  account; combined filters intersect.
- Tenant isolation: a second merchant's resources never appear and are not
  addressable as cursors (empty continuation, no leak).
- Invalid `limit` (0, 101) rejected exactly as deliveries reject it.
- Unknown/foreign `after` yields an empty page with no `Next-Cursor`.
- Bank-account listing: same chain assertions over registered accounts.

## Migration

None. `V23__payout_bank_account.sql` remains the head.
