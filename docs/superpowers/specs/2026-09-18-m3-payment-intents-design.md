# M3 — Payment intents with the PSP simulator design

**Date:** 2026-09-18 · **Status:** approved in brainstorming
**Builds on:** M1 ledger core, M2 accounts + REST skeleton

## Goal

Deliver the instant-payment core: merchants create payment intents against
their payment accounts, the PSP simulator plays the external network (the
payer pays or fails a charge), and settlement posts the balanced journal
entry that credits the merchant. Intents expire lazily; settlement is
exactly-once; the external boundary is production code, never a mock.

## Decisions (locked in brainstorming)

1. **Settlement entry** — one balanced transaction per settlement: DEBIT the
   system clearing ASSET account, CREDIT the merchant's backing payable
   (LIABILITY). No fees in M3; fee math and REVENUE accounts wait for the
   milestone that consumes them (M1 spec defers rounding to settlement
   edges).
2. **Outcome flow** — lazy settle on poll. The simulator's payer actions
   change charge state; the payments module applies outcomes when an intent
   is read (or a settle is attempted). No callbacks, no schedulers.
3. **Expiry** — lazy on access. Any read or settle attempt past
   `expires_at` transitions the intent to EXPIRED transactionally and
   refuses settlement.
4. **REST surface** — merchant: `POST /v1/payment-intents`,
   `GET /v1/payment-intents/{id}`. Simulator (the fake external world, not
   merchant-facing): `POST /simulator/charges/{id}/pay`, `/fail`,
   `GET /simulator/charges/{id}`. No cancel endpoint — expiry covers
   abandonment.

## Architecture

```
payments/                                psp-simulator/
├── domain        PaymentIntent, IntentStatus, commands,    ├── domain        SimulatedCharge, ChargeStatus
│                 exceptions                              ├── application   SimulatorService port + impl
├── application   PaymentsService port + impl;             ├── infrastructure  JdbcClient charge store
│                 PaymentNetwork port (the PSP contract)   └── interfaces     REST for the external world
├── infrastructure  JdbcClient repo, V5 migration
└── interfaces    REST /v1/payment-intents
```

**Dependency directions (ArchUnit-enforced):**
`payments → accounts.application + ledger.application` (the ports it
orchestrates); `psp-simulator → payments.application` (it implements the
consumer-owned `PaymentNetwork` port — the contract a real PSP adapter
would implement; swapping the simulator for a real adapter is one Spring
bean). Nothing depends on `psp-simulator`. Runtime cross-module table access
remains banned in every direction.

### The clearing account seed

The settlement entry needs a system ASSET account ("psp clearing"). V5
inserts it into `ledger.ledger_account` with a **fixed public UUID**
compiled into the code as a constant. The single Flyway chain is the modular
monolith's composition layer: a deterministic seed row is deployment data,
not runtime cross-module access. If multi-currency or multiple clearing
institutions ever arrive, the constant becomes a lookup — additive.

### Settlement flow (one `@Transactional` method)

1. Load intent; if not CREATED, return current state.
2. Lazy expiry: past `expires_at` → status-guarded UPDATE to EXPIRED → done.
3. Fail-fast: merchant account must exist and be ACTIVE
   (`AccountsService.get`), amount matches the charge (`getCharge` echoes it).
4. Poll `PaymentNetwork.getCharge`:
   - FAILED → status-guarded UPDATE to FAILED.
   - SUCCEEDED → post via `Ledger.post`: DEBIT clearing (fixed UUID),
     CREDIT backing payable (public UUID from `AccountsService.get`) →
     store `journal_transaction_public_id` + SETTLED in the same transaction.
   - PENDING → return CREATED.
5. A succeeded charge that cannot post (e.g. merchant account frozen between
   the fail-fast check and COMMIT — the deferred trigger rejects
   `non-ACTIVE`) leaves the intent CREATED; the HTTP call surfaces 409; the
   next poll retries. Money is held at the network until the account is
   unfrozen — the trigger is the authoritative guard, the fail-fast is the
   friendly error.

**Exactly-once settlement:** every state change is a status-guarded
single-row UPDATE (`WHERE status = 'CREATED'`, rowcount-checked). In the
settle path the journal posting happens first and the guarded UPDATE
stores `journal_transaction_public_id` + SETTLED in the same transaction;
a racing loser's guarded UPDATE matches zero rows, throws, and the
transaction rolls back its posting with everything else — the append-only
journal only ever sees committed entries. The `UNIQUE` column is the
structural backstop (a settled intent can never acquire a second journal
entry even if a future writer skips the guard).

**Status-guarded transitions everywhere** (the M2-backlog TOCTOU item):
every intent state change is a single-row conditional UPDATE with rowcount
verification — no read-then-write races between concurrent polls.

## Domain model (`payments.domain`, pure Java)

- `enum IntentStatus { CREATED, SETTLED, FAILED, EXPIRED }` — all but
  CREATED terminal.
- `record PaymentIntent(UUID publicId, UUID accountPublicId, Money amount,
  IntentStatus status, UUID chargePublicId, Instant expiresAt, Instant createdAt,
  Instant settledAt, UUID journalTransactionPublicId)`.
- `CreateIntentCommand(UUID accountPublicId, Money amount, Duration ttl)`.
- Exceptions: `UnknownPaymentIntentException(UUID)`,
  `IntentNotActiveException(UUID, IntentStatus)` (acting on a terminal
  intent), `ChargeAmountMismatchException(UUID, Money expected, Money actual)`.

## Persistence — `V5__payments_schema.sql`

```sql
create schema payments;

create table payments.payment_intent (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  account_public_id             uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  status                        text not null default 'CREATED'
                                check (status in ('CREATED','SETTLED','FAILED','EXPIRED')),
  charge_public_id              uuid not null unique,
  expires_at                    timestamptz not null,
  created_at                    timestamptz not null default now(),
  settled_at                    timestamptz,
  journal_transaction_public_id uuid unique
);

create index payment_intent_account_idx on payments.payment_intent (account_public_id);

insert into ledger.ledger_account (public_id, name, type)
values ('5f9c3b2e-0000-4000-8000-000000000001', 'psp clearing', 'ASSET');

grant usage on schema payments to nummus_app;
grant select, insert on payments.payment_intent to nummus_app;
grant update (status, settled_at, journal_transaction_public_id) on payments.payment_intent to nummus_app;
grant usage on all sequences in schema payments to nummus_app;
```

## PSP simulator (`psp-simulator` module)

`psp_simulator.charge` in its own schema (DB-backed: external state that
outlives restarts; M6 conciliation will match settlement reports against
it):

```sql
create schema psp_simulator;

create table psp_simulator.charge (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  amount      numeric(19,4) not null check (amount > 0),
  status      text not null default 'PENDING'
              check (status in ('PENDING','SUCCEEDED','FAILED')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

grant usage on schema psp_simulator to nummus_app;
grant select, insert on psp_simulator.charge to nummus_app;
grant update (status, updated_at) on psp_simulator.charge to nummus_app;
grant usage on all sequences in schema psp_simulator to nummus_app;
```

**`PaymentNetwork` port** (owned by `payments.application`):

```java
public interface PaymentNetwork {
  NetworkCharge createCharge(Money amount);
  NetworkCharge getCharge(UUID chargePublicId);
}

public record NetworkCharge(UUID publicId, Money amount, ChargeStatus status) {}
public enum ChargeStatus { PENDING, SUCCEEDED, FAILED }
```

The simulator's adapter implements it (`JdbcClient` charge store). Payer
actions (`SimulatorService.pay(UUID)` / `fail(UUID)`) are status-guarded
PENDING→terminal transitions (409 `ChargeNotPendingException` otherwise).
`getCharge` echoes the amount so payments can detect network-side mutation
(`ChargeAmountMismatchException` → 500-class invariant breach, never silent).

## REST contract

| Method | Path | Request | Success |
|---|---|---|---|
| POST | `/v1/payment-intents` | `{accountId, amount, expiresInSeconds?}` | 201 + `IntentResponse` |
| GET | `/v1/payment-intents/{id}` | — | 200 + `IntentResponse` (lazy expiry + settle applied) |
| POST | `/simulator/charges/{id}/pay` | — | 200 + `ChargeResponse` (SUCCEEDED) |
| POST | `/simulator/charges/{id}/fail` | — | 200 + `ChargeResponse` (FAILED) |
| GET | `/simulator/charges/{id}` | — | 200 + `ChargeResponse` |

`IntentResponse`: `publicId`, `accountId`, `amount`, `currency`, `status`,
`chargeId`, `expiresAt`, `createdAt`, `settledAt` (nullable). Validation:
`amount` > 0 scale ≤ 4; `expiresInSeconds` default 1800, bounds [60, 86400];
`accountId` must reference an existing, ACTIVE payment account at creation.

### Error model

The M2 `GlobalExceptionHandler` moves to a shared `interfaces` package at
the root (`com.leandrossb.nummus.interfaces`) and gains the payments
vocabulary — unknown intent/charge → 404; act on terminal intent or
non-PENDING charge → 409; validation failures → 400; commit-time trigger
failures → 409 (unchanged). Migrating it is the M2-backlog scoping item
landing now because a second module's controllers exist.

## Testing

- **Unit** (fakes for `Ledger`, `AccountsService`, `PaymentNetwork`):
  full state machine, expiry bounds, fail-fast paths, mismatch invariant.
- **Integration** (Testcontainers + MockMvc):
  create → simulator pay → GET settles → merchant balance credited (natural
  sign); expire → settle refused; simulator fail → FAILED; **frozen-account
  settle → 409, unfreeze → next GET settles** (the M2-backlog end-to-end
  commit-time trigger case); concurrent settle race → exactly one journal
  entry and one SETTLED read; simulator 409 on non-PENDING actions.
- **ArchUnit**: rules extended to `payments` and `psp-simulator` with the
  dependency directions above.
- **Success criteria**: `./mvnw verify` green; exactly-once settlement
  proven under concurrency; the external boundary exercised only through
  its REST surface in integration tests.

## Out of scope (recorded to prevent re-litigation)

- Idempotency keys and response replay (M4 — merchant-facing writes are
  deliberately non-idempotent until then).
- Webhooks to merchants on intent state changes (M5).
- Fees, fee REVENUE accounts, rounding (later, with conciliation).
- Cancel endpoint (expiry covers abandonment; add when a consumer exists).
- Scheduled expiry sweeps (lazy expiry is eventually consistent on access;
  a sweep is additive later).
- Multiple clearing accounts / institutions (constant becomes a lookup).
