# M19 — Money-Out Conciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settlement reports cover every instruction the network executed — charges (today) plus payout transfers and charge refunds — by re-keying report lines from a charge id to a subject `(type, network instruction id)`.

**Architecture:** Payments first exposes windowed settled-listing feeds for its two money-out lifecycles (unconsumed, independently green); then one compile-coupled generalization lands the V21 schema swap, the subject-keyed matcher vocabulary, the simulator's three-way report union, and the re-shaped REST contracts; docs close the milestone.

**Tech Stack:** Java 25, Spring Boot (`@Transactional`, `JdbcClient`), PostgreSQL + Flyway (V21 — backfill + renames + constraint swap), JUnit 5 + Testcontainers, MockMvc, `ApiDrivers` fixtures.

**Spec:** `docs/superpowers/specs/2026-09-23-m19-money-out-conciliation-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing anywhere.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m19-conciliation`, replace `<Tests>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m19-conciliation origin/worktree-m19-conciliation && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Tests>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once. Remote verify ≈ 5 min.
- **TDD strictly:** failing tests (remote RED), implement, focused GREEN, full verify, commit. Test-first commits use a `test:` prefix; the implementation lands as the task's `feat:` commit. Both carry the trailer.
- **No new dependencies.** Baseline: **437 tests, all green** (verified on merged main `712ffa6`). Running totals below are provisional — the authoritative total is 437 + your cumulative `@Test` method count; report the true number.
- **Worktree:** execution starts from a worktree on branch `worktree-m19-conciliation`. Never commit on `main` (docs commits excepted, per house precedent).
- Standing idioms: `ApiDrivers`, loopback URLs, jsonPath reads, shared-container delta counting (membership pins, never global size pins, inside now-windows), `@AfterAll` sweeps carried with every copied recipe, SQL-tamper divergence fixtures, 30s back-margin on window starts.
- Money is `Money.ofBrl(...)`/`Money.of(BigDecimal, BRL)`; comparisons via `compareTo`; never `double`.
- The matcher stays pure; persistence stays write-once (select/insert only); ingest stays one transaction.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V21__money_out_conciliation.sql (new, T2)
src/main/java/com/leandrossb/nummus/payments/application/MoneyOutSettlementView.java (new, T1)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsService.java (modify, T1 — +listSettlements)
src/main/java/com/leandrossb/nummus/payments/application/RefundsService.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsRepository.java (modify, T1 — +findSettledBetween)
src/main/java/com/leandrossb/nummus/payments/application/RefundsRepository.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPayoutsRepository.java (modify, T1)
src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientRefundsRepository.java (modify, T1)
src/test/java/com/leandrossb/nummus/payments/application/InMemoryPayoutsRepository.java (modify, T1)
src/test/java/com/leandrossb/nummus/payments/application/InMemoryRefundsRepository.java (modify, T1)
src/test/java/com/leandrossb/nummus/conciliation/MoneyOutSettlementQueryTest.java (new, T1)
src/main/java/com/leandrossb/nummus/conciliation/application/SubjectType.java (new, T2)
src/main/java/com/leandrossb/nummus/conciliation/application/SubjectRef.java (new, T2)
src/main/java/com/leandrossb/nummus/conciliation/application/InternalSettlement.java (new, T2)
src/main/java/com/leandrossb/nummus/conciliation/application/NetworkSettlement.java (rewrite, T2 — subject-shaped)
src/main/java/com/leandrossb/nummus/conciliation/application/MatchedLine.java (rewrite, T2)
src/main/java/com/leandrossb/nummus/conciliation/application/ReportMatcher.java (rewrite, T2 — subject-keyed)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java (modify, T2 — three feeds)
src/main/java/com/leandrossb/nummus/conciliation/infrastructure/JdbcClientConciliationStore.java (modify, T2)
src/main/java/com/leandrossb/nummus/conciliation/infrastructure/SimulatorSettlementReportSource.java (modify, T2)
src/main/java/com/leandrossb/nummus/conciliation/interfaces/dto/ReportLineResponse.java (rewrite, T2)
src/main/java/com/leandrossb/nummus/psp_simulator/application/NetworkSettlement.java (rewrite, T2 — +kind)
src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorService.java (modify, T2 — javadoc)
src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorServiceImpl.java (modify, T2 — union)
src/main/java/com/leandrossb/nummus/psp_simulator/application/TransferStore.java (modify, T2 — +findSucceededBetween)
src/main/java/com/leandrossb/nummus/psp_simulator/application/RefundStore.java (modify, T2)
src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/JdbcClientTransferStore.java (modify, T2)
src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/JdbcClientRefundStore.java (modify, T2)
src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/SimulatorReportController.java (modify, T2 — response shape)
src/test/java/com/leandrossb/nummus/conciliation/ReportMatcherTest.java (rewrite+extend, T2)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationStoreTest.java (modify, T2)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationSchemaTest.java (rewrite, T2)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationRestApiTest.java (modify, T2 — +4 methods, sweep extension)
src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorRestApiTest.java (modify, T2)
README.md, docs/m2-backlog.md (modify, T3)
```

(Exact helper names inside the two simulator JDBC stores may differ — grep before editing; the interfaces below are the contract.)

---

### Task 1: Payments money-out settlement feeds

**Files:**
- Create: `MoneyOutSettlementView.java` (payments/application), `MoneyOutSettlementQueryTest.java` (conciliation test package — mirrors `PaymentsSettlementQueryTest`'s placement)
- Modify: `PayoutsService.java`, `RefundsService.java`, `PayoutsRepository.java`, `RefundsRepository.java`, `PayoutsServiceImpl.java`, `RefundsServiceImpl.java`, `JdbcClientPayoutsRepository.java`, `JdbcClientRefundsRepository.java`, `InMemoryPayoutsRepository.java`, `InMemoryRefundsRepository.java`
- Test: the new query suite (2 methods)

**Interfaces:**
- Produces: `MoneyOutSettlementView(UUID internalPublicId, UUID networkInstructionPublicId, Money amount, Instant settledAt)`; `PayoutsService.listSettlements(Instant from, Instant to)` → `List<MoneyOutSettlementView>` (payout public id + its transfer id); `RefundsService.listSettlements(Instant from, Instant to)` → same record (refund public id + its network refund id); repository ports gain `List<Payout> findSettledBetween(Instant, Instant)` / `List<Refund> findSettledBetween(Instant, Instant)`. Nothing consumes these yet — Task 2 wires them into conciliation.

- [ ] **Step 1: Write the failing tests** — create `src/test/java/com/leandrossb/nummus/conciliation/MoneyOutSettlementQueryTest.java`:

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import com.leandrossb.nummus.payments.domain.Refund;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MoneyOutSettlementQueryTest extends IntegrationTestBase {

  /** Fixtures this class settles or executes — backdated in the sweep so no
   *  other class's now-window ever sees them. */
  private static final List<UUID> settledIntents = new ArrayList<>();
  private static final List<UUID> networkCharges = new ArrayList<>();
  private static final List<UUID> settledPayouts = new ArrayList<>();
  private static final List<UUID> paidTransfers = new ArrayList<>();
  private static final List<UUID> settledRefunds = new ArrayList<>();
  private static final List<UUID> paidNetworkRefunds = new ArrayList<>();

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  /** Funds a fresh account with a settled 1000 charge, then pays `amount` out. */
  private Payout settlePayout(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Money-Out Query Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    settledPayouts.add(settled.publicId());
    return settled;
  }

  @Test
  void payoutListSettlementsHonorsHalfOpenWindowAndSettledOnly() {
    var settled = settlePayout("30.0000");
    Instant past = Instant.now().minusSeconds(3600);

    assertEquals(0, payouts.listSettlements(past.minusSeconds(60), past).size());

    // Membership, not a global size pin: the shared container carries other
    // classes' settlements inside any now-window, and method order is not
    // specified. The pre-fixture past window (above) stays empty regardless.
    var all = payouts.listSettlements(past, Instant.now().plusSeconds(60));
    var view = all.stream().filter(v -> v.internalPublicId().equals(settled.publicId()))
        .findFirst().orElseThrow();
    assertEquals(settled.transferPublicId(), view.networkInstructionPublicId());
    assertEquals(Money.ofBrl("30.0000").amount(), view.amount().amount());
    assertTrue(view.settledAt() != null);
  }

  @Test
  void refundListSettlementsHonorsHalfOpenWindowAndSettledOnly() {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Refund Query Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    settledRefunds.add(settled.publicId());
    Instant past = Instant.now().minusSeconds(3600);

    assertEquals(0, refunds.listSettlements(past.minusSeconds(60), past).size());

    var all = refunds.listSettlements(past, Instant.now().plusSeconds(60));
    var view = all.stream().filter(v -> v.internalPublicId().equals(settled.publicId()))
        .findFirst().orElseThrow();
    assertEquals(settled.networkRefundPublicId(), view.networkInstructionPublicId());
    assertEquals(Money.ofBrl("10.0000").amount(), view.amount().amount());
    assertTrue(view.settledAt() != null);
  }

  /** The container is shared across classes and later suites assert over
   *  now-relative windows. Push this class's fixtures two hours back — the
   *  same DB-side rewrite the conciliation divergence setups use. */
  @AfterAll
  static void moveFixturesOutOfNowWindows() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      if (!settledIntents.isEmpty()) {
        st.executeUpdate("UPDATE payments.payment_intent SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledIntents) + ")");
      }
      if (!networkCharges.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(networkCharges) + ")");
      }
      if (!settledPayouts.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledPayouts) + ")");
      }
      if (!paidTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidTransfers) + ")");
      }
      if (!settledRefunds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledRefunds) + ")");
      }
      if (!paidNetworkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidNetworkRefunds) + ")");
      }
    }
  }

  private static String quoted(List<UUID> ids) {
    return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (compile: `listSettlements`/`findSettledBetween` absent on the four types).

- [ ] **Step 3: Implement** — in order:

  1. New `src/main/java/com/leandrossb/nummus/payments/application/MoneyOutSettlementView.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A settled payout or refund as seen by conciliation — the money-out
 *  settlement record, keyed by the network instruction it executed. */
public record MoneyOutSettlementView(
    UUID internalPublicId, UUID networkInstructionPublicId,
    Money amount, Instant settledAt) {
}
```

  2. `PayoutsService.java` — add imports `java.time.Instant` and `java.util.List`, then:

```java
  /** Settled payouts in [from, to) — conciliation's view of internal money-out
   *  settlements, keyed by the network transfer each payout executed. */
  List<MoneyOutSettlementView> listSettlements(Instant from, Instant to);
```

  3. `RefundsService.java` — same imports, then:

```java
  /** Settled refunds in [from, to) — conciliation's view of internal money-out
   *  settlements, keyed by the network refund each settlement executed. */
  List<MoneyOutSettlementView> listSettlements(Instant from, Instant to);
```

  4. `PayoutsRepository.java` / `RefundsRepository.java` — add imports `java.time.Instant`, `java.util.List`, then on each:

```java
  /** SETTLED rows with settled_at in [from, to), ordered by settled_at then id. */
  List<Payout> findSettledBetween(Instant from, Instant to);   // PayoutsRepository
  List<Refund> findSettledBetween(Instant from, Instant to);   // RefundsRepository
```

  5. `JdbcClientPayoutsRepository.java` — add `import java.util.List;`, add (column list copied verbatim from `findByPublicId`):

```java
  @Override
  public List<Payout> findSettledBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, destination_bank_key, status,
               transfer_public_id, expires_at, created_at, settled_at, fee_amount,
               request_transaction_public_id, execute_transaction_public_id,
               return_transaction_public_id
        from payments.payout
        where status = 'SETTLED' and settled_at >= :from and settled_at < :to
        order by settled_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapPayout(rs))
        .list();
  }
```

  6. `JdbcClientRefundsRepository.java` — same shape over `payments.refund` (columns copied from its `findByPublicId`), mapping with its `mapRefund`:

```java
  @Override
  public List<Refund> findSettledBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, intent_public_id, amount, status, network_refund_public_id,
               expires_at, created_at, settled_at, hold_transaction_public_id,
               execute_transaction_public_id, return_transaction_public_id
        from payments.refund
        where status = 'SETTLED' and settled_at >= :from and settled_at < :to
        order by settled_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapRefund(rs))
        .list();
  }
```

  7. `PayoutsServiceImpl.java` — add after `get` (mirrors `PaymentsServiceImpl.listSettlements`, `@Transactional` and null-guards included):

```java
  @Override
  @Transactional
  public List<MoneyOutSettlementView> listSettlements(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return repository.findSettledBetween(from, to).stream()
        .map(payout -> new MoneyOutSettlementView(payout.publicId(), payout.transferPublicId(),
            payout.amount(), payout.settledAt()))
        .toList();
  }
```

  8. `RefundsServiceImpl.java` — same, with `refund.publicId(), refund.networkRefundPublicId()`.

  9. `InMemoryPayoutsRepository.java` (test fake) — add `import java.util.Comparator;` and `import java.util.List;`:

```java
  @Override
  public List<Payout> findSettledBetween(Instant from, Instant to) {
    return payouts.values().stream()
        .filter(p -> p.status() == PayoutStatus.SETTLED)
        .filter(p -> !p.settledAt().isBefore(from) && p.settledAt().isBefore(to))
        .sorted(Comparator.comparing(Payout::settledAt))
        .toList();
  }
```

  10. `InMemoryRefundsRepository.java` — same with `RefundStatus.SETTLED` and `Refund::settledAt`.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='MoneyOutSettlementQueryTest,PayoutLifecycleTest,RefundLifecycleTest,PayoutsServiceImplTest,RefundsServiceImplTest'` → green; full verify → BUILD SUCCESS, **439 tests** (437 + 2, provisional).

- [ ] **Step 5: Commit** — `feat: expose money-out settlement feeds for conciliation` + trailer; push.

---

### Task 2: V21 + the subject-keyed generalization

One compile-coupled cluster: the conciliation vocabulary, matcher, service wiring, persistence, REST shapes, the simulator report union, and the migration all reference each other — they land together or not at all.

**Files:**
- Create: `V21__money_out_conciliation.sql`, `SubjectType.java`, `SubjectRef.java`, `InternalSettlement.java` (conciliation/application)
- Rewrite: `NetworkSettlement.java` (conciliation), `MatchedLine.java`, `ReportMatcher.java`, `ReportLineResponse.java`, `NetworkSettlement.java` (psp_simulator), `ConciliationSchemaTest.java`, `ReportMatcherTest.java`
- Modify: `ConciliationService.java`, `JdbcClientConciliationStore.java`, `SimulatorSettlementReportSource.java`, `SimulatorService.java` (javadoc), `SimulatorServiceImpl.java`, `TransferStore.java`, `RefundStore.java`, `JdbcClientTransferStore.java`, `JdbcClientRefundStore.java`, `SimulatorReportController.java`, `ConciliationStoreTest.java`, `ConciliationRestApiTest.java`, `SimulatorRestApiTest.java`
- Test: +7 methods across the suites (2 matcher, 4 REST, 1 simulator)

**Interfaces:**
- Consumes: T1's `MoneyOutSettlementView` feeds.
- Produces: `SubjectType` enum (`CHARGE`, `PAYOUT_TRANSFER`, `CHARGE_REFUND`); `SubjectRef(SubjectType, UUID)`; `InternalSettlement(SubjectType, UUID internalPublicId, UUID subjectPublicId, Money amount)`; conciliation `NetworkSettlement(SubjectType, UUID subjectPublicId, Money, Instant)`; `MatchedLine(String origin, SubjectType, UUID subjectPublicId, Money reported, UUID internalPublicId, Money internal, String matchStatus)`; `ReportMatcher.match(SettlementReport, List<InternalSettlement>)`; psp `NetworkSettlement(String kind, UUID subjectPublicId, Money, Instant)` with kind ∈ the three enum names; report-line REST fields `subjectType`/`subjectId`/`internalId`; simulator report field `kind` + `subjectId`.

- [ ] **Step 1: Write the failing tests** — in order (each listed change is the full set for that file):

  1. **`ReportMatcherTest.java` — rewrite.** Helpers take a kind; the four existing methods keep their bodies, constructed with `SubjectType.CHARGE`; `statusOf`/`originOf` filter on `l.subjectPublicId()`. New helpers and methods:

```java
  private static InternalSettlement settled(SubjectType kind, UUID subjectId, String amount) {
    return new InternalSettlement(kind, UUID.randomUUID(), subjectId,
        Money.ofBrl(amount));
  }

  private static NetworkSettlement line(SubjectType kind, UUID subjectId, String amount,
      Instant at) {
    return new NetworkSettlement(kind, subjectId, Money.ofBrl(amount), at);
  }

  @Test
  void sameIdUnderDifferentKindsIsNotADuplicate() {
    var shared = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE, shared, "5.0000", FROM.plusSeconds(10)),
        line(SubjectType.PAYOUT_TRANSFER, shared, "5.0000", FROM.plusSeconds(20))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(SubjectType.CHARGE, shared, "5.0000"),
        settled(SubjectType.PAYOUT_TRANSFER, shared, "5.0000"),
        settled(SubjectType.CHARGE_REFUND, UUID.randomUUID(), "7.0000"))); // unmatched

    assertEquals(3, outcome.lines().size());
    assertTrue(outcome.summary().conciled() == false);
    assertEquals(1, outcome.summary().missingExternal());
    var orphan = outcome.lines().stream()
        .filter(l -> l.subjectType() == SubjectType.CHARGE_REFUND).findFirst().orElseThrow();
    assertEquals("MISSING_EXTERNAL", orphan.matchStatus());
  }

  @Test
  void duplicateWithinAKindIsRejected() {
    var refund = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        line(SubjectType.CHARGE_REFUND, refund, "5.0000", FROM.plusSeconds(10)),
        line(SubjectType.CHARGE_REFUND, refund, "5.0000", FROM.plusSeconds(20))));

    var duplicate = assertThrows(DuplicateSettlementLinesException.class,
        () -> ReportMatcher.match(report, List.of()));
    assertTrue(duplicate.getMessage().contains(refund.toString()));
  }
```

  (Imports: `SubjectType`, `InternalSettlement` from `conciliation.application`; drop the `SettlementView` import.)

  2. **`ConciliationSchemaTest.java` — rewrite** the single test to the new columns and add the kind dimension:

```java
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, subject_type,"
          + " subject_public_id, reported_amount, match_status)"
          + " VALUES (" + rowId + ", 'EXTERNAL', 'CHARGE', '" + charge + "', 10.0000, 'MATCHED')");
      SQLException badOrigin = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, subject_type, subject_public_id, match_status)"
              + " VALUES (" + rowId + ", 'OTHER', 'CHARGE', '" + UUID.randomUUID() + "', 'MATCHED')"));
      assertEquals("23514", badOrigin.getSQLState());
      SQLException badKind = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, subject_type, subject_public_id, match_status)"
              + " VALUES (" + rowId + ", 'EXTERNAL', 'WIRE', '" + UUID.randomUUID() + "', 'MATCHED')"));
      assertEquals("23514", badKind.getSQLState());
      SQLException duplicateSubject = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, subject_type, subject_public_id,"
              + " reported_amount, match_status)"
              + " VALUES (" + rowId + ", 'EXTERNAL', 'CHARGE', '" + charge + "', 9.0000, 'AMOUNT_MISMATCH')"));
      assertEquals("23505", duplicateSubject.getSQLState());
      // Same id under a different kind is a different subject — inserts clean.
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, subject_type, subject_public_id,"
          + " reported_amount, match_status)"
          + " VALUES (" + rowId + ", 'EXTERNAL', 'CHARGE_REFUND', '" + charge + "', 10.0000, 'MATCHED')");
      try (ResultSet rs = st.executeQuery(
          "SELECT internal_public_id, internal_amount FROM conciliation.report_line"
              + " WHERE report_id = " + rowId)) {
        assertTrue(rs.next());
        assertTrue(rs.getObject(1) == null);
        assertTrue(rs.getObject(2) == null);
      }
```

  3. **`ConciliationRestApiTest.java` — extend.** Add the four money-out fixture lists (`settledPayouts`, `paidTransfers`, `settledRefunds`, `paidNetworkRefunds` — same `ArrayList<UUID>` pattern), `PayoutsService payouts` + `RefundsService refunds` autowires, these four helpers, and four test methods:

```java
  /** Funds a fresh account with a settled 1000 charge, pays `amount` out, and
   *  reads the payout to settlement. Registers every fixture for the sweep. */
  private Payout settlePayout(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Concile Payout Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());
    settledPayouts.add(settled.publicId());
    return settled;
  }

  /** The lazy-noise shape: the transfer executes, nobody ever reads the
   *  payout, so the books never post the settlement. */
  private Payout settlePayoutUnread(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Unread Payout Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1000.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var payout = payouts.create(SeedMerchant.PUBLIC_ID,
        new CreatePayoutCommand(account.publicId(), Money.ofBrl(amount), "bank-key-1", null));
    simulator.payTransfer(payout.transferPublicId());
    paidTransfers.add(payout.transferPublicId());
    return payout;
  }

  /** Settles a fresh 50 intent, refunds `amount`, and reads the refund to
   *  settlement. Registers every fixture for the sweep. */
  private Refund settleRefund(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Concile Refund Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl(amount), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());
    settledRefunds.add(settled.publicId());
    return settled;
  }

  /** The refund flavor of the lazy-noise shape: network side executed, the
   *  refund row is never read. */
  private Refund settleRefundUnread(String amount) {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID,
        new OpenAccountCommand("Unread Refund Merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("50.0000"), null));
    settledIntents.add(intent.publicId());
    networkCharges.add(intent.chargePublicId());
    simulator.pay(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl(amount), null));
    simulator.payRefund(refund.networkRefundPublicId());
    paidNetworkRefunds.add(refund.networkRefundPublicId());
    return refund;
  }
```

```java
  @Test
  void settledPayoutsAndRefundsConcileUnderTheirKinds() throws Exception {
    Instant start = Instant.now();
    var payout = settlePayout("30.0000");
    var refund = settleRefund("10.0000");

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MATCHED' && @.subjectId == '"
                + payout.transferPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'MATCHED' && @.subjectId == '"
                + refund.networkRefundPublicId() + "')]").isNotEmpty());
  }

  @Test
  void executedButUnreadPayoutIsMissingInternal() throws Exception {
    Instant start = Instant.now();
    var payout = settlePayoutUnread("25.0000"); // payTransfer, then NEVER payouts.get

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MISSING_INTERNAL' && @.subjectId == '"
                + payout.transferPublicId() + "')]").isNotEmpty());
  }

  @Test
  void payoutAmountMismatchAndMissingExternalSurface() throws Exception {
    Instant start = Instant.now();
    var tampered = settlePayout("20.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.payout_transfer SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.transferPublicId() + "'");
    }
    var excluded = settlePayout("21.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + excluded.transferPublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'AMOUNT_MISMATCH' && @.subjectId == '"
                + tampered.transferPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'PAYOUT_TRANSFER' && @.matchStatus == 'MISSING_EXTERNAL' && @.subjectId == '"
                + excluded.transferPublicId() + "')]").isNotEmpty());
  }

  @Test
  void refundDivergencesMirrorPayouts() throws Exception {
    Instant start = Instant.now();
    var unread = settleRefundUnread("11.0000");   // payRefund, then NEVER refunds.get
    var tampered = settleRefund("12.0000");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge_refund SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.networkRefundPublicId() + "'");
    }

    String body = ingest(start.minusSeconds(30).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId).header("Authorization", operatorAuth()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'MISSING_INTERNAL' && @.subjectId == '"
                + unread.networkRefundPublicId() + "')]").isNotEmpty())
        .andExpect(jsonPath(
            "$.lines[?(@.subjectType == 'CHARGE_REFUND' && @.matchStatus == 'AMOUNT_MISMATCH' && @.subjectId == '"
                + tampered.networkRefundPublicId() + "')]").isNotEmpty());
  }
```

  (The helpers' bodies need the imports `Payout`, `Refund`, `CreatePayoutCommand`, `CreateRefundCommand`, `PayoutsService`, `RefundsService` — add them alongside the autowires. `settlePayoutUnread`/`settleRefundUnread` return the still-REQUESTED row; only their network-side fixtures are registered.) **Extend `moveFixturesOutOfNowWindows`** with the four new backdate statements, one per list:

```java
      if (!settledPayouts.isEmpty()) {
        st.executeUpdate("UPDATE payments.payout SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledPayouts) + ")");
      }
      if (!paidTransfers.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.payout_transfer SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidTransfers) + ")");
      }
      if (!settledRefunds.isEmpty()) {
        st.executeUpdate("UPDATE payments.refund SET settled_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(settledRefunds) + ")");
      }
      if (!paidNetworkRefunds.isEmpty()) {
        st.executeUpdate("UPDATE psp_simulator.charge_refund SET updated_at = now() - interval '2 hours'"
            + " WHERE public_id IN (" + quoted(paidNetworkRefunds) + ")");
      }
```

  4. **`SimulatorRestApiTest.java`** — reshape the existing `settlementReportReturnsSucceededChargesInsideWindow` jsonPaths from `@.chargeId` to `@.subjectId` and add `@.kind == 'CHARGE'` to the paid assertion; add:

```java
  @Test
  void settlementReportCoversExecutedMoneyOut() throws Exception {
    var transfer = simulator.createTransfer(Money.ofBrl("9.0000"), "bank-key-1");
    simulator.payTransfer(transfer.publicId());
    var pending = simulator.createTransfer(Money.ofBrl("8.0000"), "bank-key-1");
    var charge = simulator.create(Money.ofBrl("50.0000"));
    var refund = simulator.createRefund(charge.publicId(), Money.ofBrl("5.0000"));
    simulator.payRefund(refund.publicId());

    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", Instant.now().minusSeconds(60).toString())
            .param("to", Instant.now().plusSeconds(60).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(String.format(
            "$[?(@.kind == 'PAYOUT_TRANSFER' && @.subjectId == '%s')].amount", transfer.publicId()))
            .value(9.0000))
        .andExpect(jsonPath(String.format("$[?(@.kind == 'PAYOUT_TRANSFER' && @.subjectId == '%s')]",
            pending.publicId())).doesNotExist())
        .andExpect(jsonPath(String.format(
            "$[?(@.kind == 'CHARGE_REFUND' && @.subjectId == '%s')].amount", refund.publicId()))
            .value(5.0000));
  }
```

  (Register the paid transfer/refund in a class-level sweep if the class has one; if it has none, backdate them in the test's tail via `adminConnection()` — grep the class for the house pattern and follow it.)

  5. **`ConciliationStoreTest.java`** — reshape the two `MatchedLine` constructors to carry `SubjectType.CHARGE` / `SubjectType.PAYOUT_TRANSFER` (second line), assert `readLines` first line's `subjectType()` equals `CHARGE`, and change the source filter to `l.subjectPublicId()`.

- [ ] **Step 2: Remote RED** — push; FAIL (compile: `SubjectType`/`InternalSettlement`/new record shapes absent).

- [ ] **Step 3: Implement** — in order:

  1. **`V21__money_out_conciliation.sql`:**

```sql
-- M19 money-out conciliation: report lines key by subject (kind + network
-- instruction id) so payout transfers and charge refunds reconcile beside
-- charges. Existing lines backfill to CHARGE; the V9 grants are table-level
-- and already cover the renamed columns.

alter table conciliation.report_line
  add column subject_type text;
update conciliation.report_line set subject_type = 'CHARGE';
alter table conciliation.report_line
  alter column subject_type set not null;
alter table conciliation.report_line
  add constraint report_line_subject_type_check
  check (subject_type in ('CHARGE','PAYOUT_TRANSFER','CHARGE_REFUND'));

alter table conciliation.report_line
  drop constraint if exists report_line_report_id_charge_public_id_key;
alter table conciliation.report_line
  rename column charge_public_id to subject_public_id;
alter table conciliation.report_line
  rename column internal_intent_public_id to internal_public_id;
alter table conciliation.report_line
  add constraint report_line_unique_subject
  unique (report_id, subject_type, subject_public_id);
```

  (The inline V9 unique carries Postgres's conventional `<table>_<columns>_key` name; `if exists` guards the guess, and a wrong name fails loudly at the migration step of the RED run — read the true name from `pg_constraint` and fix before GREEN, exactly the V20 drill.)

  2. **New vocabulary** (conciliation/application): `SubjectType.java`, `SubjectRef.java`, `InternalSettlement.java`:

```java
/** What a report line reconciles: money-in charges, or the two money-out
 *  instruction kinds. The report's subject dimension. */
public enum SubjectType {
  CHARGE, PAYOUT_TRANSFER, CHARGE_REFUND
}
```

```java
/** A report line's identity: the subject kind plus the network instruction's
 *  public id — the matcher's map key and the schema's uniqueness key. */
public record SubjectRef(SubjectType type, UUID publicId) {
}
```

```java
/** One internal settlement the matcher can pair with a report line, in the
 *  subject vocabulary — produced from each lifecycle's settled-window feed. */
public record InternalSettlement(
    SubjectType subjectType, UUID internalPublicId, UUID subjectPublicId, Money amount) {
}
```

  3. **`NetworkSettlement.java` (conciliation) — rewrite:**

```java
/** One line of the network's settlement report, in conciliation's vocabulary. */
public record NetworkSettlement(SubjectType subjectType, UUID subjectPublicId, Money amount,
    Instant settledAt) {
}
```

  4. **`MatchedLine.java` — rewrite:**

```java
/** One persisted verdict. INTERNAL lines (MISSING_EXTERNAL) carry a null reportedAmount. */
public record MatchedLine(String origin, SubjectType subjectType, UUID subjectPublicId,
    Money reportedAmount, UUID internalPublicId, Money internalAmount, String matchStatus) {
}
```

  5. **`ReportMatcher.java` — rewrite the method** (class javadoc: "report lines against internal settlements of every subject kind"; keep `MatchOutcome` unchanged):

```java
  public static MatchOutcome match(SettlementReport report, List<InternalSettlement> internal) {
    Map<SubjectRef, InternalSettlement> bySubject = new HashMap<>();
    for (var view : internal) {
      bySubject.put(new SubjectRef(view.subjectType(), view.subjectPublicId()), view);
    }
    Set<SubjectRef> seenSubjects = new HashSet<>();
    List<MatchedLine> lines = new ArrayList<>();
    int matched = 0;
    int mismatched = 0;
    int missingInternal = 0;
    for (var line : report.lines()) {
      var subject = new SubjectRef(line.subjectType(), line.subjectPublicId());
      if (!seenSubjects.add(subject)) {
        throw new DuplicateSettlementLinesException(line.subjectPublicId());
      }
      var view = bySubject.remove(subject);
      if (view == null) {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), null, null, "MISSING_INTERNAL"));
        missingInternal++;
      } else if (view.amount().compareTo(line.amount()) == 0) {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), view.internalPublicId(), view.amount(), "MATCHED"));
        matched++;
      } else {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), view.internalPublicId(), view.amount(), "AMOUNT_MISMATCH"));
        mismatched++;
      }
    }
    // Whatever remains was settled internally inside the window but the network
    // never reported it.
    int missingExternal = 0;
    for (var view : bySubject.values()) {
      lines.add(new MatchedLine("INTERNAL", view.subjectType(), view.subjectPublicId(), null,
          view.internalPublicId(), view.amount(), "MISSING_EXTERNAL"));
      missingExternal++;
    }
    boolean conciled = mismatched == 0 && missingInternal == 0 && missingExternal == 0;
    return new MatchOutcome(new MatchSummary(matched, mismatched, missingInternal,
        missingExternal, conciled), List.copyOf(lines));
  }
```

  6. **`ConciliationService.java`** — add fields/constructor params `PayoutsService payouts`, `RefundsService refunds` (imports from `payments.application`), then `matchWindow` becomes:

```java
  private MatchedWindow matchWindow(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = new ArrayList<InternalSettlement>();
    payments.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.CHARGE, v.intentPublicId(), v.chargePublicId(),
            v.amount())));
    payouts.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.PAYOUT_TRANSFER, v.internalPublicId(),
            v.networkInstructionPublicId(), v.amount())));
    refunds.listSettlements(from, to).forEach(v -> internal.add(
        new InternalSettlement(SubjectType.CHARGE_REFUND, v.internalPublicId(),
            v.networkInstructionPublicId(), v.amount())));
    var outcome = ReportMatcher.match(report, List.copyOf(internal));
    boolean empty = report.lines().isEmpty() && internal.isEmpty();
    return new MatchedWindow(from, to, outcome, empty);
  }
```

  7. **`JdbcClientConciliationStore.java`** — insert gains `subject_type` (param `line.subjectType().name()`), renames params `:chargeId`→`:subjectId`, `:intentId`→`:internalId`; `findLines` selects `l.subject_type, l.subject_public_id, …, l.internal_public_id, …` and maps `SubjectType.valueOf(rs.getString(2))` in position 2 of the `MatchedLine`. Import `SubjectType`.

  8. **`ReportLineResponse.java` — rewrite:**

```java
public record ReportLineResponse(
    String origin, String subjectType, UUID subjectId, BigDecimal reportedAmount,
    UUID internalId, BigDecimal internalAmount, String matchStatus) {

  public static ReportLineResponse from(MatchedLine line) {
    return new ReportLineResponse(line.origin(), line.subjectType().name(),
        line.subjectPublicId(),
        line.reportedAmount() == null ? null : line.reportedAmount().amount(),
        line.internalPublicId(),
        line.internalAmount() == null ? null : line.internalAmount().amount(),
        line.matchStatus());
  }
}
```

  9. **`NetworkSettlement.java` (psp_simulator) — rewrite:**

```java
/** What the network says it settled: a SUCCEEDED instruction in a window —
 *  kind is CHARGE, PAYOUT_TRANSFER, or CHARGE_REFUND. */
public record NetworkSettlement(String kind, UUID subjectPublicId, Money amount, Instant settledAt) {
}
```

  10. **`SimulatorService.java`** — javadoc only: "The network's settlement report: SUCCEEDED charges, payout transfers, and charge refunds in [from, to), each line carrying its kind."

  11. **`SimulatorServiceImpl.settlementReport`** — the union (add `import java.util.ArrayList;`):

```java
  @Override
  @Transactional(readOnly = true)
  public List<NetworkSettlement> settlementReport(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    var lines = new ArrayList<NetworkSettlement>();
    chargeStore.findSucceededBetween(from, to).forEach(charge -> lines.add(
        new NetworkSettlement("CHARGE", charge.publicId(), charge.amount(), charge.updatedAt())));
    transferStore.findSucceededBetween(from, to).forEach(transfer -> lines.add(
        new NetworkSettlement("PAYOUT_TRANSFER", transfer.publicId(), transfer.amount(),
            transfer.updatedAt())));
    refundStore.findSucceededBetween(from, to).forEach(refund -> lines.add(
        new NetworkSettlement("CHARGE_REFUND", refund.publicId(), refund.amount(),
            refund.updatedAt())));
    return List.copyOf(lines);
  }
```

  12. **`TransferStore.java` / `RefundStore.java`** — add imports `java.time.Instant`, `java.util.List`, and (javadoc mirroring `ChargeStore`):

```java
  /** SUCCEEDED transfers with updated_at in [from, to), ordered by updated_at then id. */
  List<SimulatedTransfer> findSucceededBetween(Instant from, Instant to);
```

```java
  /** SUCCEEDED refunds with updated_at in [from, to), ordered by updated_at then id. */
  List<SimulatedRefund> findSucceededBetween(Instant from, Instant to);
```

  13. **`JdbcClientTransferStore.java` / `JdbcClientRefundStore.java`** — add the query mirroring `JdbcClientChargeStore.findSucceededBetween` exactly (column list copied from each store's `findByPublicId`, row mapper and `toOffsetDateTime` reused — grep the file for the helper names):

```java
  @Override
  public List<SimulatedTransfer> findSucceededBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, amount, destination_bank_key, status, created_at, updated_at
        from psp_simulator.payout_transfer
        where status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to
        order by updated_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapTransfer(rs))
        .list();
  }
```

```java
  @Override
  public List<SimulatedRefund> findSucceededBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, charge_public_id, amount, status, created_at, updated_at
        from psp_simulator.charge_refund
        where status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to
        order by updated_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapRefund(rs))
        .list();
  }
```

  14. **`SimulatorReportController.java`** — response record becomes:

```java
  record NetworkSettlementResponse(String kind, java.util.UUID subjectId,
      java.math.BigDecimal amount, String currency, Instant settledAt) {

    static NetworkSettlementResponse from(NetworkSettlement settlement) {
      return new NetworkSettlementResponse(settlement.kind(), settlement.subjectPublicId(),
          settlement.amount().amount(), settlement.amount().currency().getCurrencyCode(),
          settlement.settledAt());
    }
  }
```

  15. **`SimulatorSettlementReportSource.java`** — mapping becomes `SubjectType.valueOf(settlement.kind())` for the first component; import `SubjectType`.

  **Spec test drop, recorded:** the spec's "Backfill pin" (a pre-V21-shaped report reading as CHARGE) is not implementable on fresh-database CI — Flyway boots V21 over an empty `report_line`, so no pre-V21 row can exist to read back. The backfill UPDATE is total (no WHERE) and deterministic; the CHARGE read contract stays pinned by every reshaped charge assertion in `ConciliationRestApiTest`/`ConciliationStoreTest`. Dropped deliberately, recorded here per the M11-review lesson.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ReportMatcherTest,ConciliationStoreTest,ConciliationSchemaTest,ConciliationRestApiTest,ConciliationAlertsTest,ConciliationWorkerTest,SimulatorRestApiTest'` → green; full verify → BUILD SUCCESS, **446 tests** (439 + 7, provisional).

- [ ] **Step 5: Commit** — `feat: reconcile money-out instructions in settlement reports (V21)` + trailer; push.

---

### Task 3: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — the **Conciliation** capability row extends with `; reports cover every executed instruction — charges, payout transfers, and charge refunds` (semicolon-clause style); Status gains `- [x] M19 — Money-out conciliation`.
- Modify: `docs/m2-backlog.md` — append at the end of the file:

```markdown
## From the M19 design

M19 closed the M16/M17 conciliation bound: settlement reports cover every
instruction the network executed — charges, payout transfers, and charge
refunds — over the same window, marker, and digest. Report lines key by
subject (kind + network instruction id); the matcher, schema, and REST
shapes generalized from the charge-shaped key. Known bounds, deliberate:

- **Strict mirror semantics** — internal SETTLED vs network SUCCEEDED only.
  An executed-but-never-read money-out instruction surfaces as
  MISSING_INTERNAL with a frozen verdict (the M6 stance for charges,
  sharpened: reservation legs keep the money accounted, so this flags
  reconciliation debt, not lost money; reading the resource settles it).
- **No per-kind tally split** — digest and summary stay aggregate counts.
- **No retroactive re-match** — pre-M19 reports keep their verdicts under
  the backfilled shape (the M6 ingest-only bound carries).
- **Clock-domain bound carries** — settled_at (JVM) vs updated_at (DB) at
  window edges for the two new kinds; the 30s lag absorbs it, and a real
  PSP adapter still owes the settlement-timestamp contract.
```

- [ ] **Step 1: Remote full verify** — `Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (N = 437 + cumulative; per-task totals are provisional).
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M19 money-out conciliation complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| Internal feeds for money-out (payments listSettlements ×2) | 1 |
| V21 subject-shaped lines + backfill + uniqueness | 2 |
| Matcher re-keyed by (kind, id); per-kind duplicate/leftover | 2 |
| Simulator report union (3 stores) + kind on the wire | 2 |
| ConciliationService three-feed wiring; worker/digest/marker unchanged | 2 |
| REST re-shapes (report lines, simulator report) | 2 |
| Divergence pins (MATCHED per kind, lazy MISSING_INTERNAL, MISMATCH, MISSING_EXTERNAL via tamper) | 2 |
| README + backlog + final gate | 3 |
