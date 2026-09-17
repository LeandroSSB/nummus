# M1 — Ledger Core: Design

Date: 2026-09-16
Status: approved (brainstorming complete)
Milestone: M1 — journal, invariants, derived balances

## Context and scope

nummus needs its accounting spine before anything else: an immutable double-entry
journal where every money movement is a balanced transaction, and balances are
derived — never stored as mutable state. M1 delivers that spine as the `ledger`
module plus the repository bootstrap it sits on.

In scope:

- Maven/Spring Boot project bootstrap (wrapper, Flyway, Testcontainers wiring).
- `ledger` module: domain model, persistence, internal service API.
- Database-level invariant enforcement (balanced transactions, append-only journal).
- Derived balances via SQL aggregation.
- Compensating-entry reversals.

Out of scope (later milestones):

- HTTP endpoints of any kind (M2 introduces the REST skeleton).
- Merchant-facing accounts module (M2) — `ledger` owns its own chart of accounts.
- Idempotency for callers (M4): the ledger API is deliberately non-idempotent.
- Balance snapshots / materialized balances (see "Future evolution").
- Multi-currency (BRL only; schema makes that a one-line relaxation later).

M1's consumer is the integration test suite. The service API is the contract that
M2+ modules will call through.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| M1 shape | Bootstrap + ledger as internal Java API; no HTTP | Each milestone ends testable end-to-end via integration tests |
| Amount storage | `NUMERIC(19,4)` | Centavo-exact BRL plus headroom for fee math (percent fees produce sub-centavo values); rounding happens only at settlement edges (M6) |
| Data access | Spring `JdbcClient` + records | Append-only aggregate: ORM update machinery is dead weight; explicit SQL keeps flush ordering fully under our control against deferred constraints |
| DB enforcement | Deferred constraint trigger + immutability triggers + role separation | Invariants must hold even against raw SQL and the schema owner; the app role lacks UPDATE/DELETE on the journal entirely |
| Balance derivation | Pure SQL aggregation over postings | The journal is the only source of truth; a wrong balance is impossible by construction. Cost is O(postings per account) — irrelevant at nummus scale for every milestone through M6 |
| Posting shape | Direction enum + strictly positive amount | Explicit accounting language; single invariant expression `Σ debits = Σ credits` |

Alternatives considered for balance derivation: per-posting running balances
(O(1) reads, but serializes writers per account and hides derived state inside
the journal) and derivation-plus-snapshots (solves scale that does not exist
yet). Both rejected; evolution path documented below.

## Architecture and project structure

Single Maven project, single deployable, packages per bounded module — the
modular monolith's first brick. Package root: `com.leandrossb.nummus`.

```
com.leandrossb.nummus.ledger
├── domain           // pure Java: Money, Direction, AccountType, LedgerAccount,
│                    // Posting, JournalTransaction, commands, exceptions
├── application      // Ledger port (interface) + implementation: fail-fast
│                    // validation, transaction orchestration
└── infrastructure   // JdbcClient repositories, Flyway migrations, Spring wiring
```

- `ledger.domain` has zero Spring/JDBC imports.
- Multi-module Maven split is deferred: with one bounded module it is indirection
  without payoff. When M2 introduces `accounts`, decide between package
  boundaries + ArchUnit guard or a Maven multi-module split. This is a recorded
  decision point, not pre-decided.
- The host has no global Maven; the wrapper is bootstrapped through a one-shot
  Maven container (implementation-plan detail).
- Flyway migrations under `src/main/resources/db/migration`; Testcontainers uses
  a pinned PostgreSQL 18 image. Spring Boot/JDK versions pinned in the plan
  (current stable line supporting Java 25).

## Domain model

- **`Money`** — record `(BigDecimal amount, Currency currency)`. Validates
  scale ≤ 4. `add`/`subtract` check currency match. Equality and comparisons
  always via `compareTo` — never `BigDecimal.equals` (scale-sensitive:
  `2.10 ≠ 2.1`).
- **`Direction`** — `DEBIT | CREDIT`.
- **`AccountType`** — `ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE`, each
  carrying its `normalBalance` (DR/CR). No redundant column in the database.
- **`LedgerAccount`** — `publicId (UUID)`, `name`, `type`, `currency (BRL)`,
  `status (ACTIVE | FROZEN | CLOSED)`, `openedAt`, `closedAt`.
- **`Posting`** — account + `Direction` + `Money` strictly positive.
- **`JournalTransaction`** — `publicId (UUID)`, `memo`, `bookedAt`, optional
  `reversalOf`, list of 2+ postings.
- **Commands** — `OpenAccountCommand`, `PostTransactionCommand` (memo +
  posting drafts), `ReverseTransactionCommand`.

Invariants validated in code (fail-fast, before touching the database):

1. At least 2 postings, with at least one DEBIT and one CREDIT.
2. Σ debits = Σ credits (via `compareTo`).
3. Single currency: BRL.

The same invariants are re-asserted by the database at commit (below) — the code
path gives fast, typed errors; the database guarantees them against every
writer.

Same-account postings on both sides of one transaction are **permitted** in M1:
harmless to the invariants. If product rules later require prohibiting it, it
is one additional check.

## Database schema and enforcement

Module-owned namespace: PostgreSQL schema `ledger`.

```sql
create table ledger.ledger_account (
  id         bigint generated always as identity primary key,
  public_id  uuid not null default gen_random_uuid() unique,
  name       text not null,
  type       text not null check (type in ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
  currency   char(3) not null default 'BRL' check (currency = 'BRL'),
  status     text not null default 'ACTIVE' check (status in ('ACTIVE','FROZEN','CLOSED')),
  opened_at  timestamptz not null default now(),
  closed_at  timestamptz
);

create table ledger.journal_transaction (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  memo        text,
  booked_at   timestamptz not null default now(),
  reversal_of bigint references ledger.journal_transaction(id),
  unique (reversal_of)          -- a transaction is reversible at most once
);

create table ledger.journal_posting (
  id             bigint generated always as identity primary key,
  transaction_id bigint not null references ledger.journal_transaction(id),
  account_id     bigint not null references ledger.ledger_account(id),
  direction      text not null check (direction in ('DEBIT','CREDIT')),
  amount         numeric(19,4) not null check (amount > 0)
);

create index journal_posting_account_idx
  on ledger.journal_posting (account_id) include (direction, amount);
create index journal_posting_transaction_idx
  on ledger.journal_posting (transaction_id);
```

Key points:

- **Currency lives only on the account.** `CHECK (currency = 'BRL')` there makes
  the whole journal BRL by construction — no redundant columns. Multi-currency
  later = one migration relaxing the check and adding currency where needed.
- **IDs**: internal `bigint identity` (insert locality, cheap FKs) plus unique
  `public_id uuid`. Future REST APIs expose only the UUID, never the sequence.
- **Balance derivation**:
  `SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)` filtered by
  account; the covering index serves the query without heap access. The raw
  DR−CR signed result is what the port returns; readers apply the account
  type's `normalBalance` when a "natural" sign is wanted (M2 concern).

### Enforcement — three layers

1. **Balanced-transaction constraint trigger** (`DEFERRABLE INITIALLY DEFERRED`)
   on `journal_posting`, validated at commit per affected transaction:
   ≥ 2 postings, ≥ 1 DEBIT and ≥ 1 CREDIT, Σ debits = Σ credits (exact numeric
   equality), all referenced accounts `ACTIVE`.
2. **Immutability triggers**: `BEFORE UPDATE OR DELETE` on `journal_transaction`
   and `journal_posting` always raise. On `ledger_account`, `DELETE` is blocked
   but `UPDATE` is allowed — freezing/closing an account is a legitimate
   operational transition, not a mutation of the past.
3. **Role separation**: Flyway runs as `nummus_owner` (owns schema, tables,
   triggers); the application connects as `nummus_app` with `SELECT` on all
   tables, `INSERT` on the three tables, `USAGE` on sequences, and a
   **column-level** `UPDATE (status, closed_at)` on `ledger_account`. On the
   journal tables, `UPDATE`/`DELETE` do not exist as privileges. The deferred
   trigger executes as invoker and only needs the `SELECT`s the role already
   holds.

### Reversals

A reversal is a new transaction with mirrored postings (each original posting
on the opposite side) and `reversal_of` pointing at the original. The unique
constraint on `reversal_of` makes a double reversal a constraint violation at
commit (multiple NULLs do not collide, so non-reversal transactions are
unaffected). Reversing a reversal is legal — every transaction remains
reversible exactly once, so chains restore prior states.

## Internal API

```java
public interface Ledger {
  LedgerAccount openAccount(OpenAccountCommand cmd);        // name, type, currency (BRL)
  LedgerAccount freezeAccount(UUID publicId);
  LedgerAccount closeAccount(UUID publicId);                // blocks new postings only

  PostedTransaction post(PostTransactionCommand cmd);       // fail-fast validation, single DB transaction
  PostedTransaction reverse(UUID transactionPublicId, String memo);

  Money balance(UUID accountPublicId);                      // raw DR−CR
  AccountStatement statement(UUID accountPublicId, Page page); // account's postings, newest first
  PostedTransaction getTransaction(UUID txPublicId);
}
```

- `post` inserts transaction + postings in one database transaction; the
  deferred trigger is the commit-time safety net.
- The ledger API is **deliberately non-idempotent**: retry semantics belong to
  the merchant-facing idempotency layer (M4). Recorded here so nobody "fixes"
  it later.
- Domain exceptions: `UnbalancedTransactionException`,
  `TooFewPostingsException`, `UnknownAccountException`,
  `AccountNotActiveException`, `TransactionAlreadyReversedException`,
  `CurrencyMismatchException`. SQLSTATE codes from constraint/unique violations
  map to these — raw `DataAccessException` never crosses the port.

## Concurrency and error handling

- Concurrent writers do not contend for correctness: there is no balance column
  to lose an update to; parallel transactions on the same accounts only append
  postings, each satisfying the invariant independently. Final balance
  converges by construction.
- The one real race is double reversal (unique constraint): one caller commits,
  the other gets `TransactionAlreadyReversedException`. Defined behavior, no
  application-level locking.
- Status transitions: `CLOSED` is terminal; `FROZEN ↔ ACTIVE` is allowed
  (freezing is operational, not accounting).

## Testing strategy

- **Unit** (no database): `Money` (scale, currency, `compareTo` semantics),
  fail-fast validation of `post`.
- **Integration** (Testcontainers, PostgreSQL 18):
  - Balanced transaction commits; **unbalanced is rejected by the trigger at
    commit even via raw SQL bypassing the service** — proof the database
    defends itself.
  - **Immutability, two proofs**: as `nummus_app` (privilege absent) and as
    owner (trigger fires) — `UPDATE` and `DELETE` on journal tables both fail.
  - Balance derivation: posting sequence → exact balance; after reversal →
    balance returns to pre-transaction value.
  - Double reversal → `TransactionAlreadyReversedException`; reversal chain →
    allowed.
  - Postings to FROZEN/CLOSED/unknown accounts → domain exceptions.
  - **Concurrency**: N threads posting to shared accounts → all commit, final
    balance is the exact sum (BigDecimal); no deadlock.
  - Flyway migrations run clean from zero; roles created with exactly the
    specified grants.

## Success criteria

`./mvnw verify` green with the suite above, and the central invariant proven by
test rather than assertion: **an unbalanced transaction cannot be committed,
and the journal is immutable even to its owner.**

## Future evolution (out of M1 scope, recorded to prevent re-litigation)

- **Balance snapshots/checkpoints**: when aggregation cost matters, add an
  `(account, as_of_transaction, balance)` snapshot table that truncates scans.
  Additive by design — derivation stays authoritative.
- **Multi-currency**: relax `CHECK (currency = 'BRL')`, add currency to
  transactions, extend the balanced-transaction trigger to group by currency.
- **Module boundary enforcement**: ArchUnit vs Maven multi-module — decided when
  the second bounded module arrives (M2).
- **Effective/value dating**: `booked_at` only in M1; an `effective_at`
  dimension is additive if reporting ever needs it.
