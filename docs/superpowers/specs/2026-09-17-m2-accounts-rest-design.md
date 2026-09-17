# M2 — Accounts and REST API skeleton design

**Date:** 2026-09-17 · **Status:** approved in brainstorming
**Builds on:** M1 ledger core (`2026-09-16-m1-ledger-core-design.md`)

## Goal

Deliver the second bounded module — `accounts` — and the merchant-facing REST
skeleton. A payment account wraps one backing ledger account; merchants (or,
for now, any caller) open accounts, read derived balances and statements, and
drive the account lifecycle over HTTP. No money moves in M2 — that arrives
with payment intents in M3.

## Decisions (locked in brainstorming)

1. **Minimal holder model.** One entity: `PaymentAccount` with a holder
   display name. Merchant identity (document, e-mail, KYC) is deferred until
   a consumer exists (payments, webhooks).
2. **Full lifecycle over REST.** Create, get, balance, statement, freeze,
   unfreeze, close.
3. **Unfreeze exists.** `Ledger.unfreezeAccount(UUID)` joins the port and is
   exposed at REST. The M1 spec allows `FROZEN ↔ ACTIVE`; `CLOSED` stays
   terminal. Freezing without unfreeze would be a one-way trap.
4. **No authentication in M2.** Endpoints are open; this is an explicit
   milestone non-goal. Real merchant identity lands with merchant API keys
   (M4 or its own slice). No placeholder security code in the meantime.
5. **Boundaries via packages + ArchUnit.** One Maven module, one deployable;
   ArchUnit tests codify the module rules. A Maven multi-module split stays
   available later if the boundaries ever need compiler enforcement.
6. **One LIABILITY ledger account per payment account.** Funds held for a
   holder are a payable of the provider — credit-normal, textbook
   chart-of-accounts. This resolves the M2-backlog items on the unfreeze API
   and natural-sign presentation; the commit-time exception-translation item
   is resolved by the error model below.

## Architecture

```
com.leandrossb.nummus
├── ledger/                 (M1; gains one port method — unfreezeAccount)
│   ├── domain / application / infrastructure
└── accounts/               (new bounded module)
    ├── domain              PaymentAccount, AccountStatus, command, exceptions — pure Java
    ├── application         AccountsService port + implementation; calls only the Ledger port
    ├── infrastructure      JdbcClientAccountsRepository, V4 migration, Spring wiring
    └── interfaces          REST controllers, request/response DTOs, ProblemDetail advice
```

**ArchUnit rules (their own test class):**
- `..accounts..` may depend on `..ledger.application..` (the `Ledger` port)
  and on the `..ledger.domain..` types its signatures expose (`Money`,
  `Page`, `PostedTransaction`, …) — never on `..ledger.infrastructure..`.
- `..accounts.domain..` and `..ledger.domain..` have zero Spring/JDBC imports.
- JDBC/`DataSource`/`JdbcClient` types appear only in `..infrastructure..`
  packages of either module.
- `..accounts.infrastructure..` SQL touches only the `accounts` schema —
  enforced by review; the structural rules above make cross-module table
  access unreachable through Java anyway.

**Linkage and status consistency.** `accounts.payment_account` stores the
backing ledger account's **public UUID** — internal bigints never cross
modules. Every lifecycle transition is one `@Transactional` service method
that writes the accounts row and calls the `Ledger` port in the same database
transaction: both commit or both roll back. The ledger account's trigger-guarded
status is what actually blocks postings; the accounts row is the queryable
lifecycle record. The ledger account is always created first (its public UUID
is needed for the accounts insert).

## Domain model (`accounts.domain`, pure Java)

- `PaymentAccount(UUID publicId, String holderName, AccountStatus status, Instant openedAt, Instant closedAt, UUID ledgerAccountPublicId)`.
- `enum AccountStatus { ACTIVE, FROZEN, CLOSED }` — accounts owns its enum;
  values coincide with ledger's today, and the REST contract owns their
  spelling.
- `OpenAccountCommand(String holderName)`.
- `UnknownPaymentAccountException(UUID)`, `PaymentAccountNotActiveException(UUID, AccountStatus)`
  — mirroring M1's exception vocabulary so the REST layer maps both modules
  uniformly.

## Persistence — `V4__accounts_schema.sql`

```sql
create schema accounts;

create table accounts.payment_account (
  id                        bigint generated always as identity primary key,
  public_id                 uuid not null default gen_random_uuid() unique,
  holder_name               text not null check (holder_name <> ''),
  status                    text not null default 'ACTIVE'
                            check (status in ('ACTIVE','FROZEN','CLOSED')),
  ledger_account_public_id  uuid not null unique,
  opened_at                 timestamptz not null default now(),
  closed_at                 timestamptz
);

create index payment_account_holder_idx on accounts.payment_account (holder_name);

grant usage on schema accounts to nummus_app;
grant select, insert on accounts.payment_account to nummus_app;
grant update (status, closed_at) on accounts.payment_account to nummus_app;
grant usage on all sequences in schema accounts to nummus_app;
```

Least privilege mirrors V3: the app role can read, append, and transition
status — nothing else. Unlike the journal, this table legitimately mutates
(status, closed_at), so there are no immutability triggers here.

## Lifecycle semantics (application service)

- **open** — holder name required, trimmed, non-blank, ≤ 200 chars. The
  service mints the payment-account UUID first, opens the backing ledger
  account named `payable <first 8 hex chars of that UUID>` (type LIABILITY),
  then inserts the accounts row with that `public_id` as ACTIVE.
- **freeze** — CLOSED is terminal (`PaymentAccountNotActiveException`);
  otherwise dual-write FROZEN.
- **unfreeze** — allowed only from FROZEN; unfreezing an ACTIVE account
  throws `IllegalArgumentException` (invalid transition, not a lifecycle
  state), CLOSED is terminal (`PaymentAccountNotActiveException`); dual-write
  ACTIVE; `closed_at` remains null (a frozen account never had one).
- **close** — CLOSED terminal; otherwise dual-write CLOSED with
  `closed_at = now()`.
- **balance / statement** — account must exist (404 otherwise); delegate to
  `Ledger.balance` / `Ledger.statement` with the stored ledger public id.

### Natural-sign balances

The ledger port returns raw DR−CR regardless of normal side. For a
credit-normal backing account the raw sign is inverted from the holder's
perspective, so the accounts service presents the **natural sign** (the rule
the M1 spec reserved for M2): available funds read positive. The conversion
(negate when the account's normal balance is CREDIT) applies to the balance
figure in both the balance and statement responses, and lives in
`AccountsServiceImpl`; the `Ledger` port stays raw.

## REST contract (`accounts.interfaces`)

Base path `/v1/accounts`, JSON in/out, `spring-boot-starter-web`.

| Method | Path | Request | Success |
|---|---|---|---|
| POST | `/v1/accounts` | `{ "holderName": string }` | 201 + `AccountResponse` |
| GET | `/v1/accounts/{id}` | — | 200 + `AccountResponse` |
| GET | `/v1/accounts/{id}/balance` | — | 200 + `{ "amount", "currency" }` |
| GET | `/v1/accounts/{id}/statement?offset=&limit=` | offset ≥ 0, 1 ≤ limit ≤ 500 (default 50) | 200 + `{ "balance", "lines": [...] }` newest first |
| POST | `/v1/accounts/{id}/freeze` | — | 200 + `AccountResponse` |
| POST | `/v1/accounts/{id}/unfreeze` | — | 200 + `AccountResponse` |
| POST | `/v1/accounts/{id}/close` | — | 200 + `AccountResponse` |

`AccountResponse`: `publicId`, `holderName`, `status`, `openedAt`,
`closedAt` (nullable). Statement lines expose `bookedAt`,
`transactionPublicId`, `memo`, `direction`, `amount`, `currency`. UUIDs are
the only identifiers on the wire — internal sequence values never appear.

## Error model — RFC 7807 `application/problem+json`

One `@RestControllerAdvice` using Spring's `ProblemDetail`:

| Source | Status |
|---|---|
| `UnknownPaymentAccountException`, `UnknownAccountException`, `UnknownTransactionException` | 404 |
| `PaymentAccountNotActiveException`, `AccountNotActiveException`, `TransactionAlreadyReversedException` | 409 |
| Commit-time trigger failure (V2 markers `is unbalanced` / `non-ACTIVE` in the root message of `TransactionSystemException`/`UncategorizedSQLException`) | 409 |
| `InvalidMoneyException`, `CurrencyMismatchException`, bean-validation failures, malformed UUID / query params | 400 |
| Anything else | 500 |

The commit-time row closes the M2-backlog item: deterministic violations
already fail fast in the services; this catches the mid-flight race (account
frozen between validation and COMMIT) and any future writer that bypasses
the services. Unmatched commit failures rethrow as 500.

## Ledger port changes

- `Ledger.unfreezeAccount(UUID publicId)` → `LedgerAccount`; implemented in
  `LedgerServiceImpl.transitionStatus(publicId, ACTIVE, null)` — CLOSED still
  terminal. Unit tests (fake) + integration tests mirror freeze's.
- `Ledger.getAccount(UUID publicId)` → `LedgerAccount` (throws
  `UnknownAccountException`) — the accounts service needs the backing
  account's type to apply the natural-sign rule; this is the minimal read
  that provides it.

## Testing

- **Unit** (`AccountsServiceImplTest`): real `LedgerServiceImpl` on the
  existing in-memory repository — lifecycle terminal states, open validation,
  natural-sign conversion, 404-class exceptions. No new fake required.
- **Integration** (existing Testcontainers harness, `MockMvc`):
  create → empty statement → freeze → unfreeze → close → 409 on reuse;
  natural-sign balance asserted by posting directly through the `Ledger`
  port (M2 exposes no money-movement endpoint; deposits arrive in M3).
- **Ledger**: unfreeze unit + integration coverage mirroring freeze.
- **ArchUnit**: the boundary rules as their own test class.
- **Success criteria**: `./mvnw verify` green; boundaries machine-checked;
  REST contract proven end-to-end against PostgreSQL.

## Out of scope (recorded to prevent re-litigation)

- Authentication/authorization (explicit milestone non-goal).
- Money movement of any kind — deposits, withdrawals, fees (M3).
- Merchant identity beyond holder display name (deferred to first consumer).
- Statement filtering (date ranges), cursor pagination, webhooks on lifecycle
  changes (M5).
- Multi-currency — BRL only, by construction (M1's currency check).
