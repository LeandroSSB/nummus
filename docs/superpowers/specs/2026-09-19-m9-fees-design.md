# M9 — Merchant Fees and Revenue Accounting

**Date:** 2026-09-19
**Status:** approved design, pending implementation plan

## Goal

Merchants pay a per-settlement fee: a percentage plus a fixed component, set per merchant by an operator. Settlements credit the merchant the net amount and accumulate the fee in a system revenue account, in the same balanced journal transaction. Conciliation is untouched.

## Product decisions (locked)

1. **Fee model:** percentage + fixed amount, per merchant, operator-managed (set at merchant creation, updatable later). Stored as a fraction (`0.0099` = 0.99%), not a percent value.
2. **Timing:** the fee is quoted at intent creation (estimate from the current rate) but charged at settlement using the **rate in force at settle time**. A rate change between create and settle affects the charged fee; nothing is frozen per intent.
3. **Rounding:** `HALF_UP` to centavos (scale 2), applied once, to the composed fee (`gross × rate + fixed`).
4. **Fee cap:** the charged fee never exceeds the gross amount (`fee = min(computed, gross)`), so net is always ≥ 0. Relevant only when the fixed component exceeds a small transaction.

Defaults preserve current behavior: existing merchants and new merchants created without a fee carry `rate = 0, fixed = 0` — their settlements post exactly as today.

## Data model

### Migration `V12__fees_schema.sql`

```sql
alter table merchants.merchant
  add column fee_rate  numeric(9,6)  not null default 0
    check (fee_rate >= 0 and fee_rate < 1),
  add column fee_fixed numeric(19,4) not null default 0
    check (fee_fixed >= 0);

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000002', 'payment fees', 'REVENUE');
```

- `fee_rate` is a fraction with 6 decimal places (0.0001% granularity). `fee_fixed` shares the money scale (`numeric(19,4)`).
- The revenue account is seeded by migration exactly like the V5 clearing asset: fixed public id, composition-layer seeding, runtime code never writes the ledger schema. Mirror constant: `payments.application.FeeRevenueAccount.PUBLIC_ID` (sibling of `PaymentClearingAccount`).
- `payments.payment_intent` gains `fee_amount numeric(19,4)`, nullable until settled — the charged fee is a settle-time fact; pre-settle intents store nothing.

## Module boundaries

- **merchants** owns the fee schedule storage. `merchants.application` exposes `record FeeSchedule(BigDecimal rate, BigDecimal fixedAmount)` and a lookup by merchant public id, following the existing internal-API pattern (`SeedMerchant` bridge). Plain decimals, not `Money` — the merchants module stays self-contained (ArchUnit bans merchants → ledger). `MerchantStore` and its JDBC adapter gain the columns and the update.
- **payments** owns fee computation and application. A pure calculator takes `(Money gross, FeeSchedule)` and returns `(Money fee, Money net)`. `payments → merchants.application` is an established dependency (ArchUnit-compliant).
- **ledger** changes not at all — the three-leg settle is an ordinary balanced transaction; the REVENUE account is data, not code.
- **conciliation** changes not at all: the matcher continues comparing the intent's gross amount against report lines. Fees are internal accounting, not network facts.

## Fee computation contract

```
raw    = gross × rate + fixed
fee    = min(raw.setScale(2, HALF_UP), gross)   // fee > 0
net    = gross − fee
```

- Applied once at settlement; quoted (same formula, no persistence) whenever an intent response is rendered pre-settle.
- Zero fee (rate 0, fixed 0, or raw rounding to 0): the revenue posting is omitted entirely and the settle posts two legs, identical to today's shape. The journal never carries zero-amount postings.
- Money discipline: fee is canonical at scale 2; comparisons keep the `compareTo` discipline (records' `equals` is scale-sensitive by design).

## Settlement flow

`settle()` in `PaymentsServiceImpl`:

1. Resolve the merchant's current `FeeSchedule` (account → merchant public id → merchants API).
2. Compute `(fee, net)` for `intent.amount()`.
3. Post one transaction: `DEBIT PaymentClearingAccount gross`, `CREDIT merchant ledger account net`, and — only when `fee > 0` — `CREDIT FeeRevenueAccount fee`. The ledger's balanced-to-zero invariant is the free proof that `gross = net + fee`.
4. `markSettled` also persists `fee_amount`.
5. The `SETTLED` lifecycle event carries `fee` and `netAmount` (known in `settle()`; consumers get the fact, not an estimate). Other event types carry null, as `settledAt` does today.

Race and failure semantics are unchanged: the guarded `markSettled` transition, `ConcurrentSettlementException`, and the event-on-won-transition rule apply identically.

## Quote semantics

- `POST /v1/payment-intents` and `GET /v1/payment-intents/{id}` responses gain `fee` and `netAmount`.
- Pre-settle: computed on the fly from the rate in force at read time (an estimate — stated by the field's presence alongside the intent's unsettled status).
- Post-settle: the persisted `fee_amount` (and `gross − fee`). A rate change after settlement never rewrites a settled fact.
- Idempotent replay of a create returns the originally stored response, quote included — replay-verbatim semantics already cover this.

## REST surface

**Operator** (routes under `/v1/merchants` are already operator-gated by M8):

- `PUT /v1/merchants/{id}/fee` — `@Idempotent` (NULL merchant namespace). Body: `{"rate": 0.0099, "fixedAmount": 0.39}` — both required, full replacement, same wire format as the existing amount fields (decimal numbers, `@Digits`-validated). → 200 with the merchant representation including the fee.
- `POST /v1/merchants` accepts an optional `fee` object (default `rate 0, fixed 0`).
- `GET /v1/merchants/{id}` (operator) exposes the current fee.

**Merchant:**

- Intent responses gain `fee` / `netAmount` (quote semantics above).
- Account statements and balances reflect net credits; no separate merchant-facing "fee receipt" endpoint.

## Error handling

- Fee validation (PUT and create): `rate` ∈ [0, 1), `fixedAmount` ≥ 0, both well-formed decimals → violations are 400 problem+json via bean validation, matching existing conventions. A merchant key on `/v1/merchants/*` stays 403 (M8 role mismatch).
- Settlement needs no new error paths: the cap guarantees a postable net; amount mismatch, inactive account, and concurrent-settle behavior are unchanged.

## Security and invariants

- Only operators mutate fees; merchants read their own quote through scoped intent endpoints.
- `gross = net + fee` is enforced by the ledger's write-time balance invariant — a decomposition bug cannot post, so it cannot corrupt balances.
- The revenue account is an internal ledger account; no endpoint writes to it directly.

## Testing

1. **V12 schema/roles:** defaults are `0/0`; CHECK rejects negative rate, rate ≥ 1, negative fixed; REVENUE account seeded with the fixed public id and type; `nummus_app` can update the fee columns (roles test).
2. **Calculator (pure unit):** HALF_UP boundaries (0.099→0.10, 0.094→0.09, 0.095→0.10), percent+fixed composition, zero-fee, cap-at-gross, high-scale inputs.
3. **Settlement integration:** three-leg posting with correct legs and amounts; zero-fee merchant posts two legs; `fee_amount` persisted; rate changed between create and settle charges the settle-time rate; `SETTLED` event carries fee/net.
4. **REST:** operator PUT fee (200, replay idempotent, validation 400, merchant-key 403); optional fee at merchant create; merchant sees `fee`/`netAmount` in create and GET intent (pre- and post-settle); merchant balance reflects net; revenue account balance accumulates fees (derived).
5. **Regression:** conciliation and ledger suites pass unmodified.

## Non-goals / deliberate bounds

- **No fee history or versioning** — two mutable columns; who-changed-what-when is out of scope (operators remain role-level without person attribution, an existing M8 bound).
- **No fees outside settlement** — nothing on expiry, failure, or as recurring/monthly charges.
- **No revenue reporting endpoint** — the revenue balance is derivable from the ledger; analytics can come later.
- **No fee negotiation surface for merchants** — read-only quote; only operators set rates.
