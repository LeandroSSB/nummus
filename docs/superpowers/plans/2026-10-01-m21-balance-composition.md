# M21 Balance Composition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose derived balance composition — booked `amount` plus informational `pendingIncoming` (CREATED intents) and `reservedOutgoing` (REQUESTED payouts + refunds) — on the balance and statement reads.

**Architecture:** Accounts owns a new port `MoneyInFlight` (same seam direction as `OutstandingHolds`); payments provides a JdbcClient adapter summing its own tables. `AccountsService` grows an additive `composition()` read; the statement read returns an accounts-owned record carrying the same three figures. No schema change.

**Tech Stack:** Java 25, Spring Boot (JdbcClient, MockMvc), JUnit 5, Testcontainers (PostgreSQL).

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits (`test:`, `feat:`, `docs:`).
- Money is `Money.ofBrl(...)` (`BigDecimal`); BRL only. Never `double`.
- Accounts must not depend on payments: the port interface lives in `accounts.application`, the adapter in `payments.infrastructure`.
- REST change is additive: keep the shipped field names `amount` (balance) and `balance` (statement).
- Payout sufficiency semantics do not change: `PayoutsServiceImpl` keeps calling `accounts.balance(...)`.
- No DB migration. No new webhook events.
- **Verification protocol (no local Maven):** implementer subagents edit, run no build, and commit. The controller runs all Maven commands remotely on megalan after each task:
  `ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B <branch> origin/<branch> && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<TestClass>'`
  Branches are pushed only after a task's green commit, so red intermediate `test:` commits never reach CI alone.
- Commit ordering convention (matches M20 history): commit the failing test first (`test:`), then the implementation (`feat:`), per task.

---

### Task 1: `MoneyInFlight` port and payments adapter

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/MoneyInFlight.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/infrastructure/PaymentsMoneyInFlight.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/PaymentsMoneyInFlightTest.java`

**Interfaces:**
- Consumes: nothing new (existing services for test fixtures only).
- Produces: `accounts.application.MoneyInFlight` with `Sums sums(UUID accountPublicId)` and nested record `Sums(Money pendingIncoming, Money reservedOutgoing)`; Spring component `payments.infrastructure.PaymentsMoneyInFlight implements MoneyInFlight`. Tasks 2–5 rely on these exact names.

- [ ] **Step 1: Write the failing integration test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb/nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The in-flight sums the accounts module reads: pending settlement inbound
 *  and requested-but-unexecuted outbound, per payment account. */
@AutoConfigureMockMvc
class PaymentsMoneyInFlightTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private Ledger ledger;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private MoneyInFlight moneyInFlight;

  private UUID merchantId;
  private PaymentAccount account;

  @BeforeEach
  void createFundedFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Money In Flight Fixture Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    String merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));

    account = accountsService.open(merchantId, new OpenAccountCommand("In Flight Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "in flight house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("in flight funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("200.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("200.0000")))));
  }

  @Test
  void sumsAreZeroOnAFreshAccount() {
    var sums = moneyInFlight.sums(account.publicId());
    assertEquals(Money.ofBrl("0.0000"), sums.pendingIncoming());
    assertEquals(Money.ofBrl("0.0000"), sums.reservedOutgoing());
  }

  @Test
  void sumsSplitPendingSettlementFromRequestedMoneyOut() {
    // Inbound still pending settlement: created, never paid.
    payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));

    // A settled intent funds a refund and a payout; a second created intent
    // keeps pendingIncoming honest (settled money must not count as pending).
    var settled = createAndSettleIntent("60.0000");
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        Money.ofBrl("25.0000"), destination.publicId(), Duration.ofMinutes(10)));
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("5.0000"), Duration.ofMinutes(10)));

    var sums = moneyInFlight.sums(account.publicId());
    assertEquals(Money.ofBrl("40.0000"), sums.pendingIncoming());
    assertEquals(Money.ofBrl("30.0000"), sums.reservedOutgoing());
  }

  private PaymentIntent createAndSettleIntent(String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    // Lazy terminal transition: a read resolves the paid charge into settlement.
    return payments.get(merchantId, intent.publicId());
  }
}
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/PaymentsMoneyInFlightTest.java
git commit -m "test: cover the in-flight money sums adapter"
```

- [ ] **Step 3: Write the port and adapter**

`src/main/java/com/leandrossb/nummus/accounts/application/MoneyInFlight.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Money that belongs to the account's story but is not in its booked balance:
 * charges awaiting settlement (inbound — they have not touched the account's
 * ledger rows yet) and requested payouts/refunds (outbound — already reserved
 * out of the balance, not yet executed). Accounts owns this seam — not
 * payments — because balance and statement reads are accounts operations; the
 * payments module adapts to the port (the OutstandingHolds inversion).
 */
public interface MoneyInFlight {

  /** In-flight sums for one payment account, in natural (positive) sign. */
  Sums sums(UUID accountPublicId);

  /** pendingIncoming: gross amounts of CREATED intents. reservedOutgoing:
   *  amounts of REQUESTED payouts plus REQUESTED refunds — exactly the
   *  reservation legs' totals; the payout execution fee is not included. */
  record Sums(Money pendingIncoming, Money reservedOutgoing) {}
}
```

`src/main/java/com/leandrossb/nummus/payments/infrastructure/PaymentsMoneyInFlight.java`:

```java
package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The payments module's adapter for the accounts-owned in-flight port. The
 * journal cannot answer "what is coming": an unpaid intent has no posting
 * anywhere, so lifecycle state — which payments owns — is the only source.
 * REQUESTED reservations, by contrast, are real postings; the consistency
 * guard test pins the two views together.
 */
@Component
public class PaymentsMoneyInFlight implements MoneyInFlight {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public PaymentsMoneyInFlight(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Sums sums(UUID accountPublicId) {
    var row = jdbc.sql("""
        select
          (select coalesce(sum(amount), 0) from payments.payment_intent
             where account_public_id = :accountPublicId and status = 'CREATED') as pending_incoming,
          (select coalesce(sum(amount), 0) from payments.payout
             where account_public_id = :accountPublicId and status = 'REQUESTED')
            + (select coalesce(sum(r.amount), 0) from payments.refund r
                 join payments.payment_intent i on i.public_id = r.intent_public_id
                 where i.account_public_id = :accountPublicId and r.status = 'REQUESTED')
            as reserved_outgoing
        """)
        .param("accountPublicId", accountPublicId)
        .query((rs, i) -> new BigDecimal[] {rs.getBigDecimal("pending_incoming"),
            rs.getBigDecimal("reserved_outgoing")})
        .single();
    return new Sums(Money.of(row[0], BRL), Money.of(row[1], BRL));
  }
}
```

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/application/MoneyInFlight.java \
  src/main/java/com/leandrossb/nummus/payments/infrastructure/PaymentsMoneyInFlight.java
git commit -m "feat: derive in-flight money sums per payment account"
```

- [ ] **Step 5: Controller verifies**

Controller (not the implementer) runs, after pushing the branch:
`./mvnw -B test -Dtest=PaymentsMoneyInFlightTest` via the megalan docker command in Global Constraints.
Expected: 2 tests PASS.

---

### Task 2: `AccountsService.composition()` read

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/BalanceComposition.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java`
- Test: `src/test/java/com/leandrossb/nummus/accounts/application/AccountsServiceImplTest.java`

**Interfaces:**
- Consumes: `MoneyInFlight.sums(UUID)` / `MoneyInFlight.Sums(Money, Money)` from Task 1.
- Produces: `AccountsService.composition(UUID merchantPublicId, UUID publicId)` returning `accounts.application.BalanceComposition(Money balance, Money pendingIncoming, Money reservedOutgoing)`. Task 3's controller code calls exactly this.

- [ ] **Step 1: Write the failing unit tests**

Add to `AccountsServiceImplTest` (its existing fields `ledger` and `accounts` show the construction pattern; update the `accounts` construction and add a fake):

Replace the field block:

```java
  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository(), id -> false,
          id -> new MoneyInFlight.Sums(Money.ofBrl("7.0000"), Money.ofBrl("3.0000")));
```

Add the test:

```java
  @Test
  void compositionCarriesBookedBalancePlusInFlightSums() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "composition house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("composition funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    var composition = accounts.composition(SeedMerchant.PUBLIC_ID, account.publicId());
    assertEquals(0, composition.balance().compareTo(Money.ofBrl("150.0000")));
    assertEquals(0, composition.pendingIncoming().compareTo(Money.ofBrl("7.0000")));
    assertEquals(0, composition.reservedOutgoing().compareTo(Money.ofBrl("3.0000")));
  }

  @Test
  void compositionRejectsUnknownAccount() {
    assertThrows(UnknownPaymentAccountException.class,
        () -> accounts.composition(SeedMerchant.PUBLIC_ID, UUID.randomUUID()));
  }
```

(Keep every existing test in the file unchanged apart from the constructor line.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/accounts/application/AccountsServiceImplTest.java
git commit -m "test: cover the balance composition service read"
```

- [ ] **Step 3: Implement**

`src/main/java/com/leandrossb/nummus/accounts/application/BalanceComposition.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.ledger.domain.Money;

/**
 * The three-figure answer to "how much does this account hold": booked funds
 * plus the in-flight context around them. pendingIncoming is informational —
 * not yet in balance. reservedOutgoing is informational — already deducted
 * from balance by its reservation posting. What a new payout may reserve is
 * balance alone.
 */
public record BalanceComposition(Money balance, Money pendingIncoming, Money reservedOutgoing) {}
```

Add to `AccountsService.java` (next to `balance`):

```java
  /** Booked balance plus the in-flight sums around it (see BalanceComposition). */
  BalanceComposition composition(UUID merchantPublicId, UUID publicId);
```

In `AccountsServiceImpl.java`: add field `private final MoneyInFlight moneyInFlight;`, extend the constructor parameter list with `MoneyInFlight moneyInFlight` (after `OutstandingHolds outstandingHolds`) and assign it, then add:

```java
  @Override
  @Transactional(readOnly = true)
  public BalanceComposition composition(UUID merchantPublicId, UUID publicId) {
    var account = require(merchantPublicId, publicId);
    var booked = naturalSigned(account, ledger.balance(account.ledgerAccountPublicId()));
    var inFlight = moneyInFlight.sums(publicId);
    return new BalanceComposition(booked, inFlight.pendingIncoming(), inFlight.reservedOutgoing());
  }
```

Add the import `com.leandrossb.nummus.accounts.application.BalanceComposition` only if needed (same package — it is not).

Note: `AccountsServiceImpl` is also constructed by any other test or config — grep for `new AccountsServiceImpl(` across `src/` and add the same lambda-style fake (`id -> new MoneyInFlight.Sums(Money.ofBrl("0.0000"), Money.ofBrl("0.0000"))`) wherever it appears so the tree compiles.

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/application/BalanceComposition.java \
  src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java \
  src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java
git commit -m "feat: compose booked balance with in-flight sums in the accounts service"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=AccountsServiceImplTest` on megalan. Expected: all PASS, including the two new tests.

---

### Task 3: REST surface — balance and statement carry composition

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/ComposedStatement.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/BalanceResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/StatementResponse.java`
- Test: `src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java` (additive assertions)
- Test: `src/test/java/com/leandrossb/nummus/accounts/application/AccountsServiceImplTest.java` (statement return-type adjustment)

**Interfaces:**
- Consumes: `composition(...)` and `BalanceComposition` from Task 2; `MoneyInFlight.Sums` from Task 1.
- Produces: REST contract — `GET /v1/accounts/{id}/balance` → `{amount, pendingIncoming, reservedOutgoing, currency}`; `GET /v1/accounts/{id}/statement` → `{balance, pendingIncoming, reservedOutgoing, currency, lines[]}`. `AccountsService.statement(...)` now returns `accounts.application.ComposedStatement`.

- [ ] **Step 1: Write the failing REST assertions**

In `AccountsRestApiTest.balanceAndStatementPresentNaturalSignAfterLedgerFunding`, extend the two probes:

```java
    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(150.0000))
        .andExpect(jsonPath("$.pendingIncoming").value(0.0000))
        .andExpect(jsonPath("$.reservedOutgoing").value(0.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(150.0000))
        .andExpect(jsonPath("$.pendingIncoming").value(0.0000))
        .andExpect(jsonPath("$.reservedOutgoing").value(0.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].direction").value("CREDIT"))
        .andExpect(jsonPath("$.lines[0].currency").value("BRL"));
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java
git commit -m "test: pin composition fields on the balance and statement reads"
```

- [ ] **Step 3: Implement the service-side record and signature change**

`src/main/java/com/leandrossb/nummus/accounts/application/ComposedStatement.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.util.List;

/**
 * A statement read composed with the in-flight sums, so one response answers
 * what is booked, what is coming, and what is locked — the same three figures
 * the balance read carries, over the same commit frontier.
 */
public record ComposedStatement(PaymentAccount account, Money balance, Money pendingIncoming,
    Money reservedOutgoing, List<StatementLine> lines) {}
```

In `AccountsService.java`, change the statement return type:

```java
  /** Postings newest first with the natural-signed balance and in-flight sums. */
  ComposedStatement statement(UUID merchantPublicId, UUID publicId, Page page);
```

In `AccountsServiceImpl.java`, replace the `statement` body:

```java
  @Override
  @Transactional(readOnly = true)
  public ComposedStatement statement(UUID merchantPublicId, UUID publicId, Page page) {
    Objects.requireNonNull(page, "page must not be null");
    var account = require(merchantPublicId, publicId);
    var raw = ledger.statement(account.ledgerAccountPublicId(), page);
    var inFlight = moneyInFlight.sums(publicId);
    return new ComposedStatement(account, naturalSigned(account, raw.balance()),
        inFlight.pendingIncoming(), inFlight.reservedOutgoing(), raw.lines());
  }
```

(Drop the now-unused `AccountStatement` import if the compiler flags it.)

- [ ] **Step 4: Implement the DTOs and controller**

`BalanceResponse.java` — full replacement:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.application.BalanceComposition;
import java.math.BigDecimal;

/** REST view of a derived balance in natural sign, with the in-flight sums:
 *  pendingIncoming not yet booked, reservedOutgoing already reserved out. */
public record BalanceResponse(BigDecimal amount, BigDecimal pendingIncoming,
    BigDecimal reservedOutgoing, String currency) {

  public static BalanceResponse from(BalanceComposition composition) {
    return new BalanceResponse(composition.balance().amount(),
        composition.pendingIncoming().amount(), composition.reservedOutgoing().amount(),
        composition.balance().currency().getCurrencyCode());
  }
}
```

`StatementResponse.java` — full replacement:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.application.ComposedStatement;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST view of a statement: natural-signed balance, the in-flight sums, and
 *  postings newest first. */
public record StatementResponse(BigDecimal balance, BigDecimal pendingIncoming,
    BigDecimal reservedOutgoing, String currency, List<Line> lines) {

  public static StatementResponse from(ComposedStatement statement) {
    return new StatementResponse(statement.balance().amount(),
        statement.pendingIncoming().amount(), statement.reservedOutgoing().amount(),
        statement.balance().currency().getCurrencyCode(),
        statement.lines().stream().map(Line::from).toList());
  }

  /** One posting with its originating transaction context, exactly as booked. */
  record Line(Instant bookedAt, UUID transactionPublicId, String memo, String direction,
      BigDecimal amount, String currency) {

    static Line from(StatementLine line) {
      return new Line(line.bookedAt(), line.transactionPublicId(), line.memo(),
          line.direction().name(), line.amount().amount(),
          line.amount().currency().getCurrencyCode());
    }
  }
}
```

In `AccountsController.java`, the balance handler becomes:

```java
  @GetMapping("/{id}/balance")
  BalanceResponse balance(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return BalanceResponse.from(accounts.composition(merchant.merchantPublicId(), id));
```

(keep whatever closing line exists). The statement handler keeps its
`StatementResponse.from(accounts.statement(...))` call — the types follow the new records.

Fix the one unit-test usage of the old statement type in `AccountsServiceImplTest` (`statementBalanceIsNaturalSignedWhileLinesStayAsPosted`): it reads `statement.balance()` and `statement.lines()`, which `ComposedStatement` provides with the same accessor names — no change needed unless it names `AccountStatement` explicitly (then swap the type name).

- [ ] **Step 5: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/application/ComposedStatement.java \
  src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java \
  src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java \
  src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java \
  src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/BalanceResponse.java \
  src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/StatementResponse.java
git commit -m "feat: expose balance composition on the balance and statement reads"
```

- [ ] **Step 6: Controller verifies**

`./mvnw -B test -Dtest='AccountsServiceImplTest,AccountsRestApiTest'` on megalan. Expected: all PASS (466-suite unchanged elsewhere).

---

### Task 4: Lifecycle REST test — the three figures across money movement

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/accounts/BalanceCompositionRestApiTest.java`

**Interfaces:**
- Consumes: the REST contract from Task 3; `SimulatorService.pay/payTransfer`; `ApiDrivers.registerVerifiedBankAccount`.
- Produces: the spec's lifecycle pin — nothing downstream depends on this file.

- [ ] **Step 1: Write the test**

```java
package com.leandrossb.nummus.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** One account walked through its money movements, asserting the three
 *  balance figures at every step: booked amount, pendingIncoming (not yet in
 *  balance), reservedOutgoing (already out of balance, not yet executed).
 *  The merchant carries a payoutFixedAmount fee of 1.5000 so the payout leg
 *  pins the rule the spec states: the fee debits at execution, never at
 *  reservation. */
@AutoConfigureMockMvc
class BalanceCompositionRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private UUID merchantId;
  private String merchantKey;
  private PaymentAccount account;

  @BeforeEach
  void createMerchantWithPayoutFeeFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Composition Lifecycle Merchant\","
                + "\"fee\":{\"rate\":\"0\",\"fixedAmount\":\"0\","
                + "\"payoutFixedAmount\":\"1.5000\"}}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    account = accountsService.open(merchantId, new OpenAccountCommand("Lifecycle Merchant"));
  }

  private void assertFigures(String amount, String pendingIncoming, String reservedOutgoing)
      throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(Double.parseDouble(amount)))
        .andExpect(jsonPath("$.pendingIncoming").value(Double.parseDouble(pendingIncoming)))
        .andExpect(jsonPath("$.reservedOutgoing").value(Double.parseDouble(reservedOutgoing)));
    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(Double.parseDouble(amount)))
        .andExpect(jsonPath("$.pendingIncoming").value(Double.parseDouble(pendingIncoming)))
        .andExpect(jsonPath("$.reservedOutgoing").value(Double.parseDouble(reservedOutgoing)));
  }

  @Test
  void compositionTracksTheFullMoneyLifecycle() throws Exception {
    assertFigures("0.0000", "0.0000", "0.0000");

    // Inbound: created → pendingIncoming; paid → still pendingIncoming.
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), javaMoney("100.0000"), Duration.ofMinutes(10)));
    assertFigures("0.0000", "100.0000", "0.0000");
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());
    // Settled: balance +net (rate 0, fixed 0 → net = gross), pendingIncoming −gross.
    assertFigures("100.0000", "0.0000", "0.0000");

    // Outbound: payout request reserves amount (fee NOT yet debited)…
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    Payout payout = payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        javaMoney("40.0000"), destination.publicId(), Duration.ofMinutes(10)));
    assertFigures("60.0000", "0.0000", "40.0000");

    // …execution releases the reservation and debits the fee leg.
    simulator.payTransfer(payout.transferPublicId());
    payouts.get(merchantId, payout.publicId());
    assertFigures("58.5000", "0.0000", "0.0000");

    // Refund: request reserves the amount; execution moves no further balance
    // (fees are retained, never reversed).
    var refund = refunds.create(merchantId, intent.publicId(),
        new CreateRefundCommand(javaMoney("10.0000"), Duration.ofMinutes(10)));
    assertFigures("48.5000", "0.0000", "10.0000");
    simulator.payRefund(refund.networkRefundPublicId());
    refunds.get(merchantId, refund.publicId());
    assertFigures("48.5000", "0.0000", "0.0000");
  }

  private static com.leandrossb.nummus.ledger.domain.Money javaMoney(String amount) {
    return com.leandrossb.nummus.ledger.domain.Money.ofBrl(amount);
  }
}
```

- [ ] **Step 2: Commit the test**

```bash
git add src/test/java/com/leandrossb/nummus/accounts/BalanceCompositionRestApiTest.java
git commit -m "test: pin balance composition across the money lifecycle"
```

- [ ] **Step 3: Controller verifies**

`./mvnw -B test -Dtest=BalanceCompositionRestApiTest` on megalan. Expected: PASS. If an assertion fails, the controller dispatches a fix subagent with the failure output (systematic-debugging; the figures encode spec semantics — do not adjust expectations to make it pass).

---

### Task 5: Consistency guard — payments sums equal reserved-account balances

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/accounts/BalanceCompositionConsistencyTest.java`

**Interfaces:**
- Consumes: `MoneyInFlight.sums` (Task 1), `Ledger.balance(UUID)` (existing), `PayoutReservedAccount.PUBLIC_ID` and `RefundReservedAccount.PUBLIC_ID` (existing constants in `payments.application`).
- Produces: the spec's drift guard — nothing downstream depends on this file.

- [ ] **Step 1: Write the test**

```java
package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.MoneyInFlight;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutReservedAccount;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundReservedAccount;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Reservations are real postings: while money-out is REQUESTED, the payments
 *  domain sums the API reports must equal what the reserved ledger accounts
 *  actually hold. A future change that books legs inconsistently fails here
 *  instead of drifting silently. */
@AutoConfigureMockMvc
class BalanceCompositionConsistencyTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private Ledger ledger;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private MoneyInFlight moneyInFlight;

  private UUID merchantId;
  private PaymentAccount account;

  @BeforeEach
  void createFundedFixtureWithReservations() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Consistency Guard Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));

    account = accountsService.open(merchantId, new OpenAccountCommand("Consistency Merchant"));
    var funded = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("200.0000"), Duration.ofMinutes(10)));
    simulator.pay(funded.chargePublicId());
    payments.get(merchantId, funded.publicId());

    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    payouts.create(merchantId, new CreatePayoutCommand(account.publicId(),
        Money.ofBrl("70.0000"), destination.publicId(), Duration.ofMinutes(10)));
    refunds.create(merchantId, funded.publicId(),
        new CreateRefundCommand(Money.ofBrl("30.0000"), Duration.ofMinutes(10)));
  }

  @Test
  void requestedReservationsMatchTheReservedLedgerAccounts() {
    var reservedPostings = ledger.balance(PayoutReservedAccount.PUBLIC_ID).amount()
        .add(ledger.balance(RefundReservedAccount.PUBLIC_ID).amount());
    var domainSums = moneyInFlight.sums(account.publicId()).reservedOutgoing().amount();
    assertEquals(0, reservedPostings.compareTo(domainSums),
        () -> "reserved ledger accounts hold " + reservedPostings
            + " but the payments domain reports " + domainSums);
  }
}
```

**Implementer note:** `ledger.balance(...)` returns the raw (credit-negative)
sign for those internal accounts. The reserved accounts hold credits, so their
raw balances read negative; if the assertion direction fails on first remote
run, negate the reserved sum exactly as `naturalSigned` does in
`AccountsServiceImpl` (the test's purpose is the equality, not the sign
convention) — but make no other change. Also drop the unused `List` import if
the compiler flags it.

- [ ] **Step 2: Commit the test**

```bash
git add src/test/java/com/leandrossb/nummus/accounts/BalanceCompositionConsistencyTest.java
git commit -m "test: guard reserved sums against the reserved ledger accounts"
```

- [ ] **Step 3: Controller verifies**

`./mvnw -B test -Dtest=BalanceCompositionConsistencyTest` on megalan. Expected: PASS.

---

### Task 6: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: the shipped Task 1–5 surface.
- Produces: documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table, extend the **Accounts** row's text from:

`Payment accounts for merchants, with full transaction history and derived balances`

to:

`Payment accounts for merchants, with full transaction history and derived balances — booked, pending incoming, and reserved outgoing — in one read`

In the Status list, append after the M20 line:

```markdown
- [x] M21 — Balance composition (pending/reserved views)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M21 balance composition complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify` via the Global Constraints command, minus `-Dtest`). Expected: BUILD SUCCESS, 466+8 tests, Failures: 0, Errors: 0.

---

## Final whole-branch review

After Task 6: controller generates the review package from the merge base and dispatches the final code reviewer per `superpowers:requesting-code-review`, on the most capable model, with the Minor-findings roll-up from the task reviews.
