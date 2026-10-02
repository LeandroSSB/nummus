# M27 Internal Transfers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST/GET /v1/transfers` — instant intra-merchant account-to-account money movement: one balanced journal entry committed atomically with the transfer row, idempotent, evented, listed.

**Architecture:** The payments module owns it (money movement + `payments` schema). A transfer is a single-state fact (`COMPLETED` is the only reality — no status column); the journal transaction link is the record. Sufficiency under the from-account's ledger row lock (the payout precedent minus the network legs). No caps (intra-merchant exposure unchanged — documented).

**Tech Stack:** Java 25, Spring Boot, PostgreSQL/Flyway, JUnit 5, Testcontainers.

## Global Constraints

- English everywhere. Conventional Commits.
- Money is `Money.ofBrl`; BRL only. Never `double`.
- Both accounts must be the merchant's and `ACTIVE`; `from != to`; ownership masked as 404.
- ONE balanced journal entry per transfer (debit from-account, credit to-account), same transaction as the row insert; sufficiency checked under `ledger.lockAccount(from)` before posting.
- `@Idempotent` handlers return `ResponseEntity` (the M25 lesson — replay CCE otherwise).
- Listing keyset contract verbatim (`Next-Cursor`, `limit` 1–100 default 50, exact `IllegalArgumentException` message).
- No writes before a rejection throw in the same transaction ([[transactional-poison-and-idempotent-returns]]).
- **Verification protocol (no local Maven):** implementers edit, run no build, commit (test first, then implementation). The controller verifies remotely on megalan after each task; include `ModuleBoundaryTest` in runs.

---

### Task 1: V27, domain, repository, and the transfer service

**Files:**
- Create: `src/main/resources/db/migration/V27__internal_transfers.sql`
- Create: `src/main/java/com/leandrossb/nummus/payments/domain/Transfer.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/domain/CreateTransferCommand.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/TransfersRepository.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientTransfersRepository.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/TransferEventTypes.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/TransfersService.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/TransfersServiceImpl.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/TransfersServiceTest.java`

**Interfaces:**
- Consumes: `AccountsService` (get/balance), `Ledger` (lockAccount/post), `IntentLifecycleEvents`-style publish port for the new event type (mirror how `PaymentsServiceImpl` publishes `payment_intent.*`), `Money`.
- Produces (Task 2 relies on): `TransfersService.create(UUID merchantPublicId, CreateTransferCommand cmd) → Transfer`; `get(UUID merchantPublicId, UUID publicId) → Transfer`; `list(UUID merchantPublicId, UUID after, int limit) → List<Transfer>`; `Transfer(UUID publicId, UUID merchantPublicId, UUID fromAccountPublicId, UUID toAccountPublicId, Money amount, UUID journalTransactionPublicId, Instant createdAt)`; `CreateTransferCommand(UUID fromAccountPublicId, UUID toAccountPublicId, Money amount)`; `TransferEventTypes.COMPLETED = "transfer.completed"`.

- [ ] **Step 1: Write the failing service test**

In the integration idiom (merchant fixture via operator key + POST /v1/merchants; account funding via a settled intent — `simulator.pay` + `payments.get`, the established recipe), one class covering:
1. create moves booked balances in opposite directions by exactly the amount and posts one balanced journal entry (assert both accounts' `accounts.balance` before/after; assert the transfer's `journalTransactionPublicId` resolves via `ledger.getTransaction` with exactly two postings — debit from, credit to).
2. insufficient from-account → `InsufficientFundsException`, no transfer row (count via `list` == 0), balances unchanged.
3. frozen from-account → `PaymentAccountNotActiveException`.
4. `from == to` → `IllegalArgumentException`.
5. foreign/unknown target account → `UnknownPaymentAccountException` (404 family).
6. `list` returns newest-first; `get` masks foreign transfers as unknown.

Write the full file in the sibling suites' idiom.

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/TransfersServiceTest.java
git commit -m "test: cover the internal transfer service"
```

- [ ] **Step 3: Implement**

`V27__internal_transfers.sql`:

```sql
-- M27 internal transfers: instant intra-merchant account-to-account money
-- movement. A transfer is a single-state fact — no status column: the
-- journal transaction link IS the record. merchant_public_id is
-- denormalized (the webhook-tables precedent) so listings scope without the
-- accounts seam.

create table payments.transfer (
  id                            bigint generated always as identity primary key,
  public_id                     uuid not null default gen_random_uuid() unique,
  merchant_public_id            uuid not null,
  from_account_public_id        uuid not null,
  to_account_public_id          uuid not null,
  amount                        numeric(19,4) not null check (amount > 0),
  journal_transaction_public_id uuid not null,
  created_at                    timestamptz not null default now()
);
create index transfer_merchant_idx
  on payments.transfer (merchant_public_id, id desc);

grant select, insert on payments.transfer to nummus_app;
```

`Transfer` / `CreateTransferCommand` / `TransferEventTypes` — records and constants per Produces (mirror `Payout`/`CreatePayoutCommand`/`PayoutEventTypes` shapes and javadoc style; `TransferEventTypes` has `COMPLETED` and an `ALL` set of one).

`TransfersRepository` + `JdbcClientTransfersRepository` — `insert(Transfer)`, `Optional<Transfer> findByPublicId(UUID)`, `List<Transfer> listByMerchant(UUID merchantPublicId, UUID after, int limit)` (keyset: `(:after::uuid is null or id < (select t2.id from payments.transfer t2 where t2.public_id = :after)) order by id desc limit :limit`); mapper mirroring `mapPayout`.

`TransfersServiceImpl` (constructor: `AccountsService accounts`, `Ledger ledger`, `TransfersRepository repository`, the events port mirroring `IntentLifecycleEvents` — read how `PaymentsServiceImpl` publishes and mirror a `TransferLifecycleEvents` port + outbox adapter the same way):

```java
  @Override
  @Transactional
  public Transfer create(UUID merchantPublicId, CreateTransferCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    if (cmd.fromAccountPublicId().equals(cmd.toAccountPublicId())) {
      throw new IllegalArgumentException(
          "from and to accounts must differ: " + cmd.fromAccountPublicId());
    }
    var from = requireActive(merchantPublicId, cmd.fromAccountPublicId());
    var to = requireActive(merchantPublicId, cmd.toAccountPublicId());
    // Check-then-post under the from-account's row lock — the payout
    // reservation precedent minus the network legs. The lock serializes
    // concurrent transfers and payouts against the same account.
    ledger.lockAccount(from.ledgerAccountPublicId());
    var available = accounts.balance(merchantPublicId, from.publicId());
    if (available.compareTo(cmd.amount()) < 0) {
      throw new InsufficientFundsException(from.publicId(), available, cmd.amount());
    }
    var publicId = UUID.randomUUID();
    var posted = ledger.post(new PostTransactionCommand("transfer " + publicId,
        List.of(new PostingDraft(from.ledgerAccountPublicId(), Direction.DEBIT, cmd.amount()),
            new PostingDraft(to.ledgerAccountPublicId(), Direction.CREDIT, cmd.amount()))));
    var transfer = new Transfer(publicId, merchantPublicId, from.publicId(), to.publicId(),
        cmd.amount(), posted.publicId(), Instant.now());
    var stored = repository.insert(transfer);
    events.publish(merchantPublicId, TransferEventTypes.COMPLETED, stored);
    registry.counter("nummus.transfers", "outcome", "completed").increment();
    return stored;
  }
```

(with `requireActive` mirroring the payout path's ACTIVE check → `PaymentAccountNotActiveException`; `count` helper style per the module's M24 idiom — adapt to the class's actual construction; the events port's exact method shape comes from mirroring the intent publish path).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java src/main/resources
git commit -m "feat: move booked funds between a merchant's accounts"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='TransfersServiceTest,ModuleBoundaryTest'` on megalan. Expected: PASS.

---

### Task 2: REST surface

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/TransfersController.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreateTransferRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/dto/TransferResponse.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/TransfersRestApiTest.java`

**Interfaces:**
- Consumes: Task 1's service; the M22 keyset listing contract.
- Produces: `POST /v1/transfers` (idempotent, 201 + `Location` + `TransferResponse`); `GET /v1/transfers/{id}`; `GET /v1/transfers?after=&limit=` (array + `Next-Cursor`).

- [ ] **Step 1: Write the failing REST test**

Sibling idiom (`PaymentsRestApiTest`/`RefundsRestApiTest`), one class covering:
1. create → 201, `Location`, body fields; both balances move (M21 `GET /v1/accounts/{id}/balance` reads); same-key retry replays 201 with `Idempotency-Replayed: true`.
2. GET by id → 200; foreign id (another merchant's transfer) → 404.
3. Listing walks newest-first (3 transfers, `limit=2`, distinct cursors, exactly-once).
4. Validation: amount 0 → 400; missing `toAccountId` → 400; same account twice → 400 (the `IllegalArgumentException` family as the sibling surfaces map it — verify by reading how `IllegalArgumentException` surfaces on existing routes).
5. Insufficient → 422 with detail; frozen from-account → 409.
6. The `transfer.completed` event lands in the outbox (the M25 probe idiom).

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/TransfersRestApiTest.java
git commit -m "test: cover the transfers REST surface"
```

- [ ] **Step 3: Implement the DTOs and controller**

`CreateTransferRequest` — `@NotNull UUID fromAccountId, toAccountId`, `@NotNull @DecimalMin("0.0001") @Digits(15,4) BigDecimal amount` (mirror `CreatePayoutRequest`'s annotations and messages).

`TransferResponse` — mirror `PayoutResponse`'s record/from shape over `Transfer`.

`TransfersController` — `@RequestMapping("/v1/transfers")`; `@Idempotent @PostMapping` returning `ResponseEntity<TransferResponse>` with `created(location)`; `@GetMapping("/{id}")` returning `TransferResponse`; `@GetMapping` listing with the exact keyset block (probe `limit+1`, `Next-Cursor`, the exact `IllegalArgumentException` message) — all mirroring `PayoutsController`/`PaymentsController` verbatim in style.

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/interfaces/
git commit -m "feat: expose internal transfers over REST"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=TransfersRestApiTest` on megalan. Expected: PASS.

---

### Task 3: README — capability and status entries

**Files:**
- Modify: `README.md`

- [ ] **Step 1:** In the Capabilities table, **Instant payments** row: after "per-merchant amount and rolling-daily volume caps,", insert "instant transfers between a merchant's own accounts," (keep the rest). In the Status list append after M26:

```markdown
- [x] M27 — Internal transfers (intra-merchant)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M27 internal transfers complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
