# M6 Conciliation Reports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest settlement reports emitted by the PSP simulator for a time window, match every line against internal settled intents, and persist immutable reports with per-line match status (MATCHED / AMOUNT_MISMATCH / MISSING_INTERNAL / MISSING_EXTERNAL) and a divergence summary.

**Architecture:** The simulator gains a settlement-report query (SUCCEEDED charges by settlement timestamp); `conciliation` consumes it through its own `SettlementReportSource` port (the PaymentNetwork inversion) and queries internal settlements directly from `PaymentsService.listSettlements` (the repo's internal-API idiom). A pure `ReportMatcher` computes lines and counts; report + lines insert in ONE transaction and are never updated (V9 grants carry no UPDATE).

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL via Testcontainers, JUnit 5 + MockMvc, ArchUnit. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-18-m6-conciliation-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits.
- **NO LOCAL MAVEN/JVM RUNS — ever.** The workstation runs no build compute (standing user instruction since M5). Every test run and every verify executes on the megalan server. Workflow per run:
  1. Push current commits: `git push origin HEAD:refs/heads/worktree-m6-conciliation` (WIP pushes are fine — the branch is yours; re-push after every commit you need evidence for).
  2. Remote run (single class or full suite — substitute `<GOALS>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m6-conciliation origin/worktree-m6-conciliation && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B <GOALS>'
  ```
  Examples: `<GOALS>` = `test -Dtest=ReportMatcherTest` (focused) or `verify` (full). A warm focused run takes ~1 min; full verify ~2-3 min. The output's `Tests run:`/`BUILD` lines are the TDD evidence — quote them in the report.
  - TDD adaptation: RED = push the failing test(s), remote-run the class, capture the failing `Tests run:` line; implement; re-push; GREEN; then full `verify` before the final commit.
- **Test classes end in `Test`.** Success criteria: remote `./mvnw verify` green at every task's final commit; final count **181 tests** (166 today + 15 new: 2 schema/roles, 1 payments query, 1 simulator report, 3 matcher, 2 store/source, 5 REST E2E, 1 ArchUnit).
- **Jackson 3** (`tools.jackson.databind.ObjectMapper`) where serialization appears; Jackson annotations stay `com.fasterxml.jackson.annotation.*`.
- **DB clock ~5s behind the JVM** in this environment — never mix a JVM-derived instant with a DB-side expiry check in one assertion; age rows DB-side (`now() - interval`) as the suite does.
- **Money is `BigDecimal`-based `Money`**; amount comparison is `compareTo` (scale-insensitive) — never `equals`.
- **ArchUnit** `persistenceTypesOnlyInInfrastructure`: JDBC/`java.sql` types only under `..infrastructure..`.
- **Instant query/body params are `String` + `Instant.parse`** (Spring MVC conversion of `Instant` params is not dependable); parse failures surface as `IllegalArgumentException` → 400 through the existing handler.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V9__conciliation_schema.sql            (Task 1)
src/main/java/com/leandrossb/nummus/payments/application/
  SettlementView.java                                                    (Task 2)
  PaymentsService.java  (+listSettlements)                               (Task 2)
  PaymentsServiceImpl.java (+delegation)                                 (Task 2)
  PaymentsRepository.java (+findSettledBetween)                          (Task 2)
  infrastructure/JdbcClientPaymentsRepository.java (+query)              (Task 2)
src/test/java/com/leandrossb/nummus/payments/application/
  InMemoryPaymentsRepository.java (+findSettledBetween)                  (Task 2)
src/main/java/com/leandrossb/nummus/psp_simulator/application/
  NetworkSettlement.java                                                 (Task 3)
  ChargeStore.java (+findSucceededBetween)                               (Task 3)
  SimulatorService.java (+settlementReport)                              (Task 3)
  SimulatorServiceImpl.java (+implementation)                            (Task 3)
  infrastructure/JdbcClientChargeStore.java (+query)                     (Task 3)
  interfaces/SimulatorReportController.java (new, GET settlement-report) (Task 3)
src/main/java/com/leandrossb/nummus/conciliation/
  application/NetworkSettlement.java, SettlementReport.java,
    MatchedLine.java, MatchSummary.java, ReportMatcher.java,
    DuplicateSettlementLinesException.java, UnknownConciliationReportException.java (Task 4)
  application/SettlementReportSource.java, ConciliationStore.java,
    SettlementReportSummary.java                                         (Task 5)
  infrastructure/JdbcClientConciliationStore.java,
    SimulatorSettlementReportSource.java                                 (Task 5)
  application/ConciliationService.java                                   (Task 6)
  interfaces/ConciliationReportsController.java,
    dto/CreateReportRequest.java, ReportSummaryResponse.java,
    ReportLineResponse.java                                              (Task 6)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (Task 6)
src/test/java/com/leandrossb/nummus/conciliation/
  ConciliationSchemaTest.java, ConciliationRolesTest.java                (Task 1)
  PaymentsSettlementQueryTest.java                                       (Task 2)
  ReportMatcherTest.java                                                 (Task 4)
  ConciliationStoreTest.java                                             (Task 5)
  ConciliationRestApiTest.java                                           (Task 6)
src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorRestApiTest.java (+1) (Task 3)
src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java  (Task 7)
README.md, docs/m2-backlog.md                                             (Task 8)
```

---

### Task 1: `V9__conciliation_schema.sql` + schema and roles tests

**Files:**
- Create: `src/main/resources/db/migration/V9__conciliation_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationSchemaTest.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationRolesTest.java`

**Interfaces:**
- Consumes: Flyway chain (V1–V8), `IntegrationTestBase`.
- Produces: schema `conciliation` with `settlement_report` (identity pk, `public_id uuid unique default gen_random_uuid()`, `period_from timestamptz not null`, `period_to timestamptz not null`, `status text not null check (OPEN|CONCILED)`, `matched_count/amount_mismatched_count/missing_internal_count/missing_external_count int not null`, `created_at timestamptz not null default now()`) and `report_line` (identity pk, `report_id bigint not null references settlement_report(id)`, `origin text not null check (EXTERNAL|INTERNAL)`, `charge_public_id uuid not null`, `reported_amount numeric(19,4)`, `internal_intent_public_id uuid`, `internal_amount numeric(19,4)`, `match_status text not null check (MATCHED|AMOUNT_MISMATCH|MISSING_INTERNAL|MISSING_EXTERNAL)`, `unique (report_id, charge_public_id)`). Grants `nummus_app`: `usage` on schema + `select, insert` on both tables — **no update, no delete** (write-once artifacts).

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConciliationSchemaTest extends IntegrationTestBase {

  @Test
  void schemaEnforcesChecksAndLineUniqueness() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.settlement_report (period_from, period_to, status, matched_count,"
              + " amount_mismatched_count, missing_internal_count, missing_external_count)"
              + " VALUES (now(), now() + interval '1 hour', 'WEIRD', 0, 0, 0, 0)"));
      assertEquals("23514", badStatus.getSQLState());

      String reportId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO conciliation.settlement_report (public_id, period_from, period_to, status,"
          + " matched_count, amount_mismatched_count, missing_internal_count, missing_external_count)"
          + " VALUES ('" + reportId + "', now(), now() + interval '1 hour', 'OPEN', 1, 2, 3, 4)");
      long rowId = 0;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM conciliation.settlement_report WHERE public_id = '" + reportId + "'")) {
        rs.next();
        rowId = rs.getLong(1);
      }
      String charge = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, charge_public_id,"
          + " reported_amount, match_status) VALUES (" + rowId + ", 'EXTERNAL', '" + charge + "', 10.0000, 'MATCHED')");
      SQLException badOrigin = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, charge_public_id, match_status)"
              + " VALUES (" + rowId + ", 'OTHER', '" + UUID.randomUUID() + "', 'MATCHED')"));
      assertEquals("23514", badOrigin.getSQLState());
      SQLException duplicateCharge = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO conciliation.report_line (report_id, origin, charge_public_id,"
              + " reported_amount, match_status) VALUES (" + rowId + ", 'EXTERNAL', '" + charge + "', 9.0000, 'AMOUNT_MISMATCH')"));
      assertEquals("23505", duplicateCharge.getSQLState());
      try (ResultSet rs = st.executeQuery(
          "SELECT internal_intent_public_id, internal_amount FROM conciliation.report_line"
              + " WHERE report_id = " + rowId)) {
        assertTrue(rs.next());
        assertTrue(rs.getObject(1) == null);
        assertTrue(rs.getObject(2) == null);
      }
    }
  }
}
```

- [ ] **Step 2: Remote RED**

Push (`git push origin HEAD:refs/heads/worktree-m6-conciliation`), then remote-run `test -Dtest=ConciliationSchemaTest`. Expected: FAIL — `schema "conciliation" does not exist`.

- [ ] **Step 3: Write the migration**

```sql
-- M6 conciliation: settlement reports are write-once audit artifacts. The
-- report, its lines, and the match verdicts are computed and inserted in one
-- transaction at ingest; corrections are new reports, never updates — hence
-- no update or delete grant for the application role.

create schema conciliation;

create table conciliation.settlement_report (
  id                       bigint generated always as identity primary key,
  public_id                uuid not null default gen_random_uuid() unique,
  period_from              timestamptz not null,
  period_to                timestamptz not null,
  status                   text not null check (status in ('OPEN','CONCILED')),
  matched_count            int not null,
  amount_mismatched_count  int not null,
  missing_internal_count   int not null,
  missing_external_count   int not null,
  created_at               timestamptz not null default now()
);

create table conciliation.report_line (
  id                        bigint generated always as identity primary key,
  report_id                 bigint not null references conciliation.settlement_report(id),
  origin                    text not null check (origin in ('EXTERNAL','INTERNAL')),
  charge_public_id          uuid not null,
  reported_amount           numeric(19,4),
  internal_intent_public_id uuid,
  internal_amount           numeric(19,4),
  match_status              text not null check (match_status in
                            ('MATCHED','AMOUNT_MISMATCH','MISSING_INTERNAL','MISSING_EXTERNAL')),
  unique (report_id, charge_public_id)
);

grant usage on schema conciliation to nummus_app;
grant select, insert on conciliation.settlement_report, conciliation.report_line
  to nummus_app;
```

- [ ] **Step 4: Write the roles test** (positive lifecycle + the negative UPDATE-denied pin)

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConciliationRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleInsertsAndReadsButCannotUpdateReports() throws Exception {
    String reportId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO conciliation.settlement_report (public_id, period_from, period_to,"
          + " status, matched_count, amount_mismatched_count, missing_internal_count, missing_external_count)"
          + " VALUES ('" + reportId + "', now(), now() + interval '1 hour', 'OPEN', 1, 0, 0, 0)");
      long rowId = 0;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM conciliation.settlement_report WHERE public_id = '" + reportId + "'")) {
        rs.next();
        rowId = rs.getLong(1);
      }
      st.executeUpdate("INSERT INTO conciliation.report_line (report_id, origin, charge_public_id,"
          + " reported_amount, match_status) VALUES (" + rowId + ", 'INTERNAL', '"
          + UUID.randomUUID() + "', null, 'MISSING_EXTERNAL')");
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM conciliation.report_line WHERE report_id = " + rowId)) {
        rs.next();
        assertEquals(1, rs.getInt(1));
      }
      // Write-once is enforced by privilege, not discipline.
      SQLException denied = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE conciliation.settlement_report SET status = 'CONCILED' WHERE id = " + rowId));
      assertEquals("42501", denied.getSQLState());
    }
  }
}
```

- [ ] **Step 5: Remote GREEN + full verify**

Remote `test -Dtest='ConciliationSchemaTest,ConciliationRolesTest'` → PASS (2). Then remote `verify` → `BUILD SUCCESS`, 168 tests (166 + 2).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V9__conciliation_schema.sql \
  src/test/java/com/leandrossb/nummus/conciliation/
git commit -m "feat: add the conciliation report schema (V9)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

(Then push the final commit so the remote tip matches HEAD.)

---

### Task 2: Payments settlement query (internal API extension, TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/application/SettlementView.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java`
- Modify: `src/test/java/com/leandrossb/nummus/payments/application/InMemoryPaymentsRepository.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/PaymentsSettlementQueryTest.java`

**Interfaces:**
- Consumes: `PaymentIntent`, `Money`, `IntegrationTestBase`.
- Produces (Tasks 5–6 consume): `record SettlementView(UUID intentPublicId, UUID accountPublicId, UUID chargePublicId, Money amount, Instant settledAt, UUID journalTransactionPublicId)`; `PaymentsService`: `List<SettlementView> listSettlements(Instant from, Instant to)`; `PaymentsRepository`: `List<PaymentIntent> findSettledBetween(Instant from, Instant to)` — predicate `status = 'SETTLED' and settled_at >= :from and settled_at < :to`, ordered by `settled_at, id`. The service maps intents to views.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentsSettlementQueryTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  private UUID settle(String amount) {
    var account = accountsService.open(new OpenAccountCommand("Settle Query Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl(amount), null));
    simulator.pay(intent.chargePublicId());
    payments.get(intent.publicId());
    return intent.publicId();
  }

  @Test
  void listSettlementsHonorsHalfOpenWindowAndSettledOnly() throws Exception {
    var settled = settle("6.0000");
    // A settled intent outside the window (settled in the future-proof way:
    // settle one now, then query a window that ended before now).
    Instant past = Instant.now().minusSeconds(3600);

    var none = payments.listSettlements(past.minusSeconds(60), past);
    assertEquals(0, none.size());

    var all = payments.listSettlements(past, Instant.now().plusSeconds(60));
    assertEquals(1, all.size());
    var view = all.get(0);
    assertEquals(settled, view.intentPublicId());
    assertEquals(Money.ofBrl("6.0000").amount(), view.amount().amount());
    assertTrue(view.journalTransactionPublicId() != null);
    assertTrue(view.settledAt() != null);
  }
}
```

(Add `import static org.junit.jupiter.api.Assertions.assertTrue;`. Boundary sharpness — settled exactly at `from`/`to` — is pinned at unit level in Task 4's matcher tests via the same predicate constant; here the coarse window proves wiring.)

- [ ] **Step 2: Remote RED**

Push, remote `test -Dtest=PaymentsSettlementQueryTest` → compilation FAIL (`listSettlements` absent).

- [ ] **Step 3: Implement**

`SettlementView.java`:
```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A settled payment intent as seen by conciliation — the settlement record. */
public record SettlementView(
    UUID intentPublicId, UUID accountPublicId, UUID chargePublicId,
    Money amount, Instant settledAt, UUID journalTransactionPublicId) {
}
```

`PaymentsRepository` — add:
```java
  /** SETTLED intents with settled_at in [from, to), ordered by settled_at then id. */
  List<PaymentIntent> findSettledBetween(Instant from, Instant to);
```
(and `import java.util.List;`)

`JdbcClientPaymentsRepository` — add:
```java
  @Override
  public List<PaymentIntent> findSettledBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, status, charge_public_id,
               expires_at, created_at, settled_at, journal_transaction_public_id
        from payments.payment_intent
        where status = 'SETTLED' and settled_at >= :from and settled_at < :to
        order by settled_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapIntent(rs))
        .list();
  }
```
(and `import java.util.List;`)

`PaymentsService` — add:
```java
  /** Settled intents in [from, to) — conciliation's view of internal settlements. */
  List<SettlementView> listSettlements(Instant from, Instant to);
```
(and `import java.util.List;`)

`PaymentsServiceImpl` — add (the class has no readOnly methods today; keep the write default consistent):
```java
  @Override
  @Transactional
  public List<SettlementView> listSettlements(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return repository.findSettledBetween(from, to).stream()
        .map(intent -> new SettlementView(intent.publicId(), intent.accountPublicId(),
            intent.chargePublicId(), intent.amount(), intent.settledAt(),
            intent.journalTransactionPublicId()))
        .toList();
  }
```
(and `import java.util.List;`)

`InMemoryPaymentsRepository` (test sources) — add:
```java
  @Override
  public List<PaymentIntent> findSettledBetween(Instant from, Instant to) {
    return intents.values().stream()
        .filter(i -> i.status() == IntentStatus.SETTLED)
        .filter(i -> !i.settledAt().isBefore(from) && i.settledAt().isBefore(to))
        .sorted(java.util.Comparator.comparing(PaymentIntent::settledAt))
        .toList();
  }
```
(and `import java.util.List;`)

- [ ] **Step 4: Remote GREEN + full verify**

Remote `test -Dtest=PaymentsSettlementQueryTest` → PASS (1). Remote `verify` → `BUILD SUCCESS`, 169 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/ \
  src/test/java/com/leandrossb/nummus/payments/application/InMemoryPaymentsRepository.java \
  src/test/java/com/leandrossb/nummus/conciliation/PaymentsSettlementQueryTest.java
git commit -m "feat: expose settled intents to conciliation by window

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: Simulator settlement report (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/psp_simulator/application/NetworkSettlement.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/application/ChargeStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorService.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/application/SimulatorServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/JdbcClientChargeStore.java`
- Create: `src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/SimulatorReportController.java`
- Modify: `src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorRestApiTest.java` (append one test)

**Interfaces:**
- Consumes: `SimulatedCharge` (settlement timestamp = `updatedAt` of a SUCCEEDED charge), `Money`, `ChargeStatus`.
- Produces (Tasks 5–6 consume): `record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt)` in `psp_simulator.application`; `SimulatorService.settlementReport(Instant from, Instant to)` → `List<NetworkSettlement>` (SUCCEEDED charges with `updated_at` in `[from, to)`, ordered); `ChargeStore.findSucceededBetween(Instant from, Instant to)` → `List<SimulatedCharge>` (predicate `status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to`, order `updated_at, id`); REST `GET /simulator/settlement-report?from&to` (String params, `Instant.parse`, key-free operator surface) returning `[{chargeId, amount, currency, settledAt}]`.

- [ ] **Step 1: Write the failing test** (append to `SimulatorRestApiTest`)

```java
  @Test
  void settlementReportReturnsSucceededChargesInsideWindow() throws Exception {
    var paid = simulator.create(Money.ofBrl("8.0000"));
    simulator.pay(paid.publicId());
    var pending = simulator.create(Money.ofBrl("7.0000"));
    var failed = simulator.create(Money.ofBrl("6.0000"));
    simulator.fail(failed.publicId());

    Instant from = Instant.now().minusSeconds(60);
    Instant to = Instant.now().plusSeconds(60);
    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", from.toString()).param("to", to.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath(String.format("$[?(@.chargeId == '%s')].status", pending.publicId()))
            .doesNotExist())
        .andExpect(jsonPath(String.format("$[?(@.chargeId == '%s')].amount", paid.publicId()))
            .value(8.0000));

    mockMvc.perform(get("/simulator/settlement-report")
            .param("from", from.toString())
            .param("to", Instant.now().minusSeconds(30).toString()))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }
```

(Reuse the class's existing imports; add `jsonPath`, `content`, `get`, `status` static imports and `java.time.Instant` if absent. `simulator` autowiring already exists in that class — verify and add if not.)

- [ ] **Step 2: Remote RED**

Push, remote `test -Dtest=SimulatorRestApiTest` → FAIL: 404 on `/simulator/settlement-report`.

- [ ] **Step 3: Implement**

`NetworkSettlement.java`:
```java
package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** What the network says it settled: a SUCCEEDED charge in a window. */
public record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt) {
}
```

`ChargeStore` — add:
```java
  /** SUCCEEDED charges with updated_at in [from, to), ordered by updated_at then id. */
  List<SimulatedCharge> findSucceededBetween(Instant from, Instant to);
```
(and `import java.time.Instant; import java.util.List;`)

`JdbcClientChargeStore` — add:
```java
  @Override
  public List<SimulatedCharge> findSucceededBetween(Instant from, Instant to) {
    return jdbc.sql("""
        select public_id, amount, status, created_at, updated_at
        from psp_simulator.charge
        where status = 'SUCCEEDED' and updated_at >= :from and updated_at < :to
        order by updated_at, id
        """)
        .param("from", toOffsetDateTime(from))
        .param("to", toOffsetDateTime(to))
        .query((rs, i) -> mapCharge(rs))
        .list();
  }
```
(and `import java.time.Instant; import java.util.List;` — adjust if `Instant` conflicts with the existing fully-qualified use)

`SimulatorService` — add:
```java
  /** The network's settlement report: SUCCEEDED charges in [from, to). */
  List<NetworkSettlement> settlementReport(Instant from, Instant to);
```
(and `import java.time.Instant; import java.util.List;`)

`SimulatorServiceImpl` — add:
```java
  @Override
  @Transactional(readOnly = true)
  public List<NetworkSettlement> settlementReport(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return chargeStore.findSucceededBetween(from, to).stream()
        .map(charge -> new NetworkSettlement(charge.publicId(), charge.amount(), charge.updatedAt()))
        .toList();
  }
```

`SimulatorController` — add (outside the `/simulator/charges` class mapping — a second `@GetMapping` method with a full path; `@RequestMapping` prefixes apply, so use an absolute path in its own small controller section or a full path constant. Since the class is mapped to `/simulator/charges`, place the report endpoint in the SAME class with path `/simulator/settlement-report`? The class prefix would make it `/simulator/charges/simulator/settlement-report`. Instead create the endpoint in the class WITHOUT the prefix conflict — simplest: a new tiny controller in the same file's package):

Create `src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/SimulatorReportController.java`:
```java
package com.leandrossb.nummus.psp_simulator.interfaces;

import com.leandrossb.nummus.psp_simulator.application.NetworkSettlement;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator surface: the raw settlement report the simulated network emits. */
@RestController
class SimulatorReportController {

  private final SimulatorService simulator;

  SimulatorReportController(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @GetMapping("/simulator/settlement-report")
  List<NetworkSettlementResponse> report(@RequestParam String from, @RequestParam String to) {
    var lines = simulator.settlementReport(Instant.parse(from), Instant.parse(to));
    return lines.stream().map(NetworkSettlementResponse::from).toList();
  }

  record NetworkSettlementResponse(java.util.UUID chargeId, java.math.BigDecimal amount,
      String currency, Instant settledAt) {

    static NetworkSettlementResponse from(NetworkSettlement settlement) {
      return new NetworkSettlementResponse(settlement.chargePublicId(),
          settlement.amount().amount(), settlement.amount().currency().getCurrencyCode(),
          settlement.settledAt());
    }
  }
}
```
(Remove the SimulatorController modification from this task's file list — the endpoint lands in the new controller.)

- [ ] **Step 4: Remote GREEN + full verify**

Remote `test -Dtest=SimulatorRestApiTest` → PASS. Remote `verify` → `BUILD SUCCESS`, 170 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/psp_simulator/ \
  src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorRestApiTest.java
git commit -m "feat: emit settlement reports from the PSP simulator

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: `ReportMatcher` — pure matching (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/NetworkSettlement.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/SettlementReport.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/MatchedLine.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/MatchSummary.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/DuplicateSettlementLinesException.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/UnknownConciliationReportException.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ReportMatcher.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ReportMatcherTest.java`

**Interfaces:**
- Consumes: `payments.application.SettlementView` (Task 2), `Money`.
- Produces (Tasks 5–6 consume):
  - `record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt)` (conciliation's own; the Task 5 adapter maps from the simulator's).
  - `record SettlementReport(Instant from, Instant to, List<NetworkSettlement> lines)`
  - `record MatchedLine(String origin, UUID chargePublicId, Money reportedAmount, UUID internalIntentPublicId, Money internalAmount, String matchStatus)` — `origin` ∈ `EXTERNAL|INTERNAL`, `matchStatus` ∈ the four states; INTERNAL lines carry null reportedAmount.
  - `record MatchSummary(int matched, int amountMismatched, int missingInternal, int missingExternal, boolean conciled)`
  - `DuplicateSettlementLinesException(UUID chargePublicId)` (message names the charge)
  - `UnknownConciliationReportException(UUID publicId)`
  - `ReportMatcher.match(SettlementReport report, List<SettlementView> internal)` → `MatchOutcome` (record `MatchOutcome(MatchSummary summary, List<MatchedLine> lines)` — add as a nested or top-level record in `ReportMatcher`) — throws `DuplicateSettlementLinesException` when the report repeats a charge id. Comparison uses `Money.compareTo`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.conciliation.application.DuplicateSettlementLinesException;
import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.NetworkSettlement;
import com.leandrossb.nummus.conciliation.application.ReportMatcher;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.SettlementView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportMatcherTest {

  private static final Instant FROM = Instant.parse("2026-09-18T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-18T11:00:00Z");

  private static SettlementView settled(UUID chargeId, String amount) {
    return new SettlementView(UUID.randomUUID(), UUID.randomUUID(), chargeId,
        Money.ofBrl(amount), FROM.plusSeconds(60), UUID.randomUUID());
  }

  @Test
  void matchedMismatchedAndMissingInternalPerExternalLine() {
    var ok = UUID.randomUUID();
    var wrong = UUID.randomUUID();
    var ghost = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        new NetworkSettlement(ok, Money.ofBrl("10.0000"), FROM.plusSeconds(10)),
        new NetworkSettlement(wrong, Money.ofBrl("11.0000"), FROM.plusSeconds(20)),
        new NetworkSettlement(ghost, Money.ofBrl("12.0000"), FROM.plusSeconds(30))));

    var outcome = ReportMatcher.match(report, List.of(
        settled(ok, "10.0"),            // scale differs on purpose: compareTo equality
        settled(wrong, "10.0000")));    // amounts differ

    assertEquals(3, outcome.lines().size());
    assertEquals("MATCHED", statusOf(outcome, ok));
    assertEquals("AMOUNT_MISMATCH", statusOf(outcome, wrong));
    assertEquals("MISSING_INTERNAL", statusOf(outcome, ghost));
    assertEquals(new com.leandrossb.nummus.conciliation.application.MatchSummary(1, 1, 1, 0, false),
        outcome.summary());
  }

  @Test
  void internalSettlementsAbsentFromTheReportAreMissingExternal() {
    var inReport = UUID.randomUUID();
    var absent = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO,
        List.of(new NetworkSettlement(inReport, Money.ofBrl("5.0000"), FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(settled(inReport, "5.0000"), settled(absent, "9.0000")));

    assertEquals(2, outcome.lines().size());
    assertEquals("MISSING_EXTERNAL", statusOf(outcome, absent));
    assertEquals("INTERNAL", originOf(outcome, absent));
    assertEquals(new com.leandrossb.nummus.conciliation.application.MatchSummary(1, 0, 0, 1, false),
        outcome.summary());
  }

  @Test
  void fullyMatchedReportsAreConciled() {
    var charge = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO,
        List.of(new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(10))));

    var outcome = ReportMatcher.match(report, List.of(settled(charge, "5.0000")));

    assertEquals(1, outcome.lines().size());
    assertEquals("MATCHED", statusOf(outcome, charge));
    assertTrue(outcome.summary().conciled());
    assertEquals(0, outcome.summary().missingExternal());
  }

  @Test
  void duplicateReportLinesAreRejected() {
    var charge = UUID.randomUUID();
    var report = new SettlementReport(FROM, TO, List.of(
        new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(10)),
        new NetworkSettlement(charge, Money.ofBrl("5.0000"), FROM.plusSeconds(20))));

    var duplicate = assertThrows(DuplicateSettlementLinesException.class,
        () -> ReportMatcher.match(report, List.of()));
    org.junit.jupiter.api.Assertions.assertTrue(duplicate.getMessage().contains(charge.toString()));
  }

  private static String statusOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.chargePublicId().equals(charge))
        .findFirst().orElseThrow().matchStatus();
  }

  private static String originOf(ReportMatcher.MatchOutcome outcome, UUID charge) {
    return outcome.lines().stream().filter(l -> l.chargePublicId().equals(charge))
        .findFirst().orElseThrow().origin();
  }
}
```

(Add the `assertTrue` static import for the third test.)

- [ ] **Step 2: Remote RED**

Push, remote `test -Dtest=ReportMatcherTest` → compilation FAIL (types absent).

- [ ] **Step 3: Implement**

`NetworkSettlement.java` / `SettlementReport.java` / `MatchedLine.java` / `MatchSummary.java` / `UnknownConciliationReportException.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** One line of the network's settlement report, in conciliation's vocabulary. */
public record NetworkSettlement(UUID chargePublicId, Money amount, Instant settledAt) {
}
```
```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.List;

/** The network's report for [from, to). */
public record SettlementReport(Instant from, Instant to, List<NetworkSettlement> lines) {
}
```
```java
package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** One persisted verdict. INTERNAL lines (MISSING_EXTERNAL) carry a null reportedAmount. */
public record MatchedLine(String origin, UUID chargePublicId, Money reportedAmount,
    UUID internalIntentPublicId, Money internalAmount, String matchStatus) {
}
```
```java
package com.leandrossb.nummus.conciliation.application;

/** Divergence tally; conciled iff every line matched. */
public record MatchSummary(int matched, int amountMismatched, int missingInternal,
    int missingExternal, boolean conciled) {
}
```
```java
package com.leandrossb.nummus.conciliation.application;

import java.util.UUID;

/** Raised for unknown report ids. */
public class UnknownConciliationReportException extends RuntimeException {

  public UnknownConciliationReportException(UUID publicId) {
    super("conciliation report not found: " + publicId);
  }
}
```

`DuplicateSettlementLinesException.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import java.util.UUID;

/** The network emitted the same charge twice in one report — rejected at ingest. */
public class DuplicateSettlementLinesException extends RuntimeException {

  public DuplicateSettlementLinesException(UUID chargePublicId) {
    super("duplicate settlement line for charge " + chargePublicId);
  }
}
```

`ReportMatcher.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.payments.application.SettlementView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure matching: report lines against internal settled intents. Amount equality
 * is {@code Money.compareTo} (scale-insensitive). Internal settlements absent
 * from the report become MISSING_EXTERNAL lines of origin INTERNAL.
 */
public final class ReportMatcher {

  private ReportMatcher() {
  }

  public static MatchOutcome match(SettlementReport report, List<SettlementView> internal) {
    Map<UUID, SettlementView> byCharge = new HashMap<>();
    for (var view : internal) {
      byCharge.put(view.chargePublicId(), view);
    }
    Set<UUID> seenCharges = new HashSet<>();
    List<MatchedLine> lines = new ArrayList<>();
    int matched = 0;
    int mismatched = 0;
    int missingInternal = 0;
    for (var line : report.lines()) {
      if (!seenCharges.add(line.chargePublicId())) {
        throw new DuplicateSettlementLinesException(line.chargePublicId());
      }
      var view = byCharge.remove(line.chargePublicId());
      if (view == null) {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            null, null, "MISSING_INTERNAL"));
        missingInternal++;
      } else if (view.amount().compareTo(line.amount()) == 0) {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            view.intentPublicId(), view.amount(), "MATCHED"));
        matched++;
      } else {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            view.intentPublicId(), view.amount(), "AMOUNT_MISMATCH"));
        mismatched++;
      }
    }
    // Whatever remains was settled internally inside the window but the network
    // never reported it.
    int missingExternal = 0;
    for (var view : byCharge.values()) {
      lines.add(new MatchedLine("INTERNAL", view.chargePublicId(), null,
          view.intentPublicId(), view.amount(), "MISSING_EXTERNAL"));
      missingExternal++;
    }
    boolean conciled = mismatched == 0 && missingInternal == 0 && missingExternal == 0;
    return new MatchOutcome(new MatchSummary(matched, mismatched, missingInternal,
        missingExternal, conciled), List.copyOf(lines));
  }

  /** Matching result: the persisted lines and their tally. */
  public record MatchOutcome(MatchSummary summary, List<MatchedLine> lines) {
  }
}
```

- [ ] **Step 4: Remote GREEN + full verify**

Remote `test -Dtest=ReportMatcherTest` → PASS (4). Remote `verify` → `BUILD SUCCESS`, 174 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/conciliation/ \
  src/test/java/com/leandrossb/nummus/conciliation/ReportMatcherTest.java
git commit -m "feat: match settlement report lines against internal settlements

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: `ConciliationStore` + `SettlementReportSource` (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/SettlementReportSummary.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationStore.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/SettlementReportSource.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/infrastructure/JdbcClientConciliationStore.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/infrastructure/SimulatorSettlementReportSource.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationStoreTest.java`

**Interfaces:**
- Consumes: V9 (Task 1), `MatchedLine`/`MatchSummary` (Task 4), `SimulatorService.settlementReport` (Task 3), `Money`.
- Produces (Task 6 consumes):
  - `record SettlementReportSummary(UUID publicId, Instant from, Instant to, String status, int matched, int amountMismatched, int missingInternal, int missingExternal, Instant createdAt)`
  - `interface ConciliationStore { void insert(SettlementReportSummary summary, List<MatchedLine> lines); List<SettlementReportSummary> listSummaries(int limit); Optional<SettlementReportSummary> findSummary(UUID publicId); List<MatchedLine> findLines(UUID reportPublicId); }` — `insert` joins the caller's transaction.
  - `interface SettlementReportSource { SettlementReport fetch(Instant from, Instant to); }`
  - `SimulatorSettlementReportSource` implements it by calling `SimulatorService.settlementReport` and mapping records.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.conciliation.application.SettlementReportSource;
import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConciliationStoreTest extends IntegrationTestBase {

  @Autowired
  private ConciliationStore store;

  @Autowired
  private SettlementReportSource source;

  @Autowired
  private SimulatorService simulator;

  @Test
  void insertAndReadBackReportWithLines() {
    var charge = UUID.randomUUID();
    var intent = UUID.randomUUID();
    var summary = new SettlementReportSummary(UUID.randomUUID(),
        Instant.now().minusSeconds(60), Instant.now(), "OPEN", 1, 0, 0, 1, Instant.now());
    var lines = List.of(
        new MatchedLine("EXTERNAL", charge, Money.ofBrl("10.0000"), intent,
            Money.ofBrl("10.0000"), "MATCHED"),
        new MatchedLine("INTERNAL", UUID.randomUUID(), null, UUID.randomUUID(),
            Money.ofBrl("3.0000"), "MISSING_EXTERNAL"));

    store.insert(summary, lines);

    var read = store.findSummary(summary.publicId()).orElseThrow();
    assertEquals("OPEN", read.status());
    assertEquals(1, read.matched());
    assertEquals(1, read.missingExternal());
    assertEquals(2, store.listSummaries(10).stream()
        .filter(s -> s.publicId().equals(summary.publicId())).count());
    var readLines = store.findLines(summary.publicId());
    assertEquals(2, readLines.size());
  }

  @Test
  void sourceFetchesTheSimulatorReportForTheWindow() {
    var paid = simulator.create(Money.ofBrl("4.0000"));
    simulator.pay(paid.publicId());

    var report = source.fetch(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));

    assertEquals(1, report.lines().stream()
        .filter(l -> l.chargePublicId().equals(paid.publicId())).count());
  }
}
```

- [ ] **Step 2: Remote RED**

Push, remote `test -Dtest=ConciliationStoreTest` → compilation FAIL (types absent).

- [ ] **Step 3: Implement**

`SettlementReportSummary.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.UUID;

/** The persisted report header with its divergence tally. */
public record SettlementReportSummary(
    UUID publicId, Instant from, Instant to, String status,
    int matched, int amountMismatched, int missingInternal, int missingExternal,
    Instant createdAt) {
}
```

`ConciliationStore.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-once persistence for conciliation reports. {@link #insert} joins the
 * caller's transaction — the report and its lines commit together or not at all.
 */
public interface ConciliationStore {

  void insert(SettlementReportSummary summary, List<MatchedLine> lines);

  /** Newest first. */
  List<SettlementReportSummary> listSummaries(int limit);

  Optional<SettlementReportSummary> findSummary(UUID publicId);

  /** Lines of one report, insertion order. */
  List<MatchedLine> findLines(UUID reportPublicId);
}
```

`SettlementReportSource.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;

/** Where settlement reports come from — the external payment network. */
public interface SettlementReportSource {

  SettlementReport fetch(Instant from, Instant to);
}
```

`JdbcClientConciliationStore.java`:
```java
package com.leandrossb.nummus.conciliation.infrastructure;

import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import com.leandrossb.nummus.ledger.domain.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientConciliationStore implements ConciliationStore {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public JdbcClientConciliationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SettlementReportSummary summary, List<MatchedLine> lines) {
    jdbc.sql("""
        insert into conciliation.settlement_report
          (public_id, period_from, period_to, status, matched_count,
           amount_mismatched_count, missing_internal_count, missing_external_count, created_at)
        values (:publicId, :from, :to, :status, :matched, :mismatched, :missingInternal,
                :missingExternal, :createdAt)
        """)
        .param("publicId", summary.publicId())
        .param("from", toOffsetDateTime(summary.from()))
        .param("to", toOffsetDateTime(summary.to()))
        .param("status", summary.status())
        .param("matched", summary.matched())
        .param("mismatched", summary.amountMismatched())
        .param("missingInternal", summary.missingInternal())
        .param("missingExternal", summary.missingExternal())
        .param("createdAt", toOffsetDateTime(summary.createdAt()))
        .update();
    Long reportRowId = jdbc.sql(
        "select id from conciliation.settlement_report where public_id = :publicId")
        .param("publicId", summary.publicId())
        .query(Long.class).single();
    for (var line : lines) {
      jdbc.sql("""
          insert into conciliation.report_line
            (report_id, origin, charge_public_id, reported_amount,
             internal_intent_public_id, internal_amount, match_status)
          values (:reportId, :origin, :chargeId, :reported, :intentId, :internal, :status)
          """)
          .param("reportId", reportRowId)
          .param("origin", line.origin())
          .param("chargeId", line.chargePublicId())
          .param("reported", line.reportedAmount() == null ? null : line.reportedAmount().amount())
          .param("intentId", line.internalIntentPublicId())
          .param("internal", line.internalAmount() == null ? null : line.internalAmount().amount())
          .param("status", line.matchStatus())
          .update();
    }
  }

  @Override
  public List<SettlementReportSummary> listSummaries(int limit) {
    return jdbc.sql("""
        select public_id, period_from, period_to, status, matched_count,
               amount_mismatched_count, missing_internal_count, missing_external_count, created_at
        from conciliation.settlement_report order by id desc limit :limit
        """)
        .param("limit", limit)
        .query((rs, i) -> mapSummary(rs)).list();
  }

  @Override
  public Optional<SettlementReportSummary> findSummary(UUID publicId) {
    return jdbc.sql("""
        select public_id, period_from, period_to, status, matched_count,
               amount_mismatched_count, missing_internal_count, missing_external_count, created_at
        from conciliation.settlement_report where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapSummary(rs)).optional();
  }

  @Override
  public List<MatchedLine> findLines(UUID reportPublicId) {
    return jdbc.sql("""
        select l.origin, l.charge_public_id, l.reported_amount,
               l.internal_intent_public_id, l.internal_amount, l.match_status
        from conciliation.report_line l
        join conciliation.settlement_report r on r.id = l.report_id
        where r.public_id = :reportPublicId
        order by l.id
        """)
        .param("reportPublicId", reportPublicId)
        .query((rs, i) -> new MatchedLine(rs.getString(1), rs.getObject(2, UUID.class),
            rs.getObject(3) == null ? null : Money.of(rs.getBigDecimal(3), BRL),
            rs.getObject(4, UUID.class),
            rs.getObject(5) == null ? null : Money.of(rs.getBigDecimal(5), BRL),
            rs.getString(6)))
        .list();
  }

  private static SettlementReportSummary mapSummary(ResultSet rs) throws SQLException {
    return new SettlementReportSummary(rs.getObject("public_id", UUID.class),
        rs.getObject("period_from", OffsetDateTime.class).toInstant(),
        rs.getObject("period_to", OffsetDateTime.class).toInstant(),
        rs.getString("status"), rs.getInt("matched_count"),
        rs.getInt("amount_mismatched_count"), rs.getInt("missing_internal_count"),
        rs.getInt("missing_external_count"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
```

`SimulatorSettlementReportSource.java`:
```java
package com.leandrossb.nummus.conciliation.infrastructure;

import com.leandrossb.nummus.conciliation.application.NetworkSettlement;
import com.leandrossb.nummus.conciliation.application.SettlementReport;
import com.leandrossb.nummus.conciliation.application.SettlementReportSource;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** The report source adapter: the simulated network plays the external PSP. */
@Component
public class SimulatorSettlementReportSource implements SettlementReportSource {

  private final SimulatorService simulator;

  public SimulatorSettlementReportSource(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @Override
  public SettlementReport fetch(Instant from, Instant to) {
    var lines = simulator.settlementReport(from, to).stream()
        .map(settlement -> new NetworkSettlement(settlement.chargePublicId(),
            settlement.amount(), settlement.settledAt()))
        .toList();
    return new SettlementReport(from, to, lines);
  }
}
```

- [ ] **Step 4: Remote GREEN + full verify**

Remote `test -Dtest=ConciliationStoreTest` → PASS (2). Remote `verify` → `BUILD SUCCESS`, 176 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/conciliation/ \
  src/test/java/com/leandrossb/nummus/conciliation/ConciliationStoreTest.java
git commit -m "feat: persist conciliation reports and source them from the simulator

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: `ConciliationService` + REST API (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/interfaces/ConciliationReportsController.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/interfaces/dto/CreateReportRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/interfaces/dto/ReportSummaryResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/interfaces/dto/ReportLineResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationRestApiTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–5; `@Idempotent` + the M4 filter (POST /v1/** requires `Idempotency-Key`).
- Produces: `ConciliationService.ingest(Instant from, Instant to)` → `SettlementReportSummary` (`@Transactional`: fetch → `payments.listSettlements` → `ReportMatcher.match` → `store.insert`; `from >= to` → `IllegalArgumentException`); REST `POST /v1/conciliation/reports` (`@Idempotent`, 201 + summary), `GET /v1/conciliation/reports` (summaries, newest first), `GET /{id}` (summary + lines; 404 via `UnknownConciliationReportException`); handler entries: `DuplicateSettlementLinesException` → 400, `UnknownConciliationReportException` → existing notFound group.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  private String ingest(String from, String to) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andReturn();
    return result.getResponse().getContentAsString();
  }

  @Test
  void settledIntentsConcileAndReplayIdempotently() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Concile Merchant"));
    var first = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("11.0000"), null));
    var second = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("12.0000"), null));
    simulator.pay(first.chargePublicId());
    simulator.pay(second.chargePublicId());
    payments.get(first.publicId());
    payments.get(second.publicId());

    String from = Instant.now().minusSeconds(3600).toString();
    String to = Instant.now().plusSeconds(60).toString();
    String body = "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    var created = mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONCILED"))
        .andExpect(jsonPath("$.matched").value(2))
        .andExpect(jsonPath("$.missingExternal").value(0))
        .andReturn().getResponse().getContentAsString();
    String reportId = com.jayway.jsonpath.JsonPath.read(created, "$.reportId");

    // Idempotent replay returns the stored response verbatim.
    String replayKey = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/conciliation/reports").header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/conciliation/reports").header(KEY, replayKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.reportId").value(reportId));

    mockMvc.perform(get("/v1/conciliation/reports"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].reportId").value(reportId));
    mockMvc.perform(get("/v1/conciliation/reports/" + reportId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MATCHED')]").isNotEmpty());
  }

  @Test
  void divergencesSurfacePerLine() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Divergence Merchant"));
    // MISSING_INTERNAL: the network settled a charge no intent knows about.
    simulator.pay(simulator.create(Money.ofBrl("77.0000")).publicId());
    // AMOUNT_MISMATCH: settle internally, then tamper the network's amount DB-side.
    var tampered = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("20.0000"), null));
    simulator.pay(tampered.chargePublicId());
    payments.get(tampered.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET amount = amount + 1"
          + " WHERE public_id = '" + tampered.chargePublicId() + "'");
    }
    // MISSING_EXTERNAL: the intent settles inside the window, but the network's
    // line for its charge sits outside the report window (DB-side rewrite of the
    // charge's settlement timestamp only — the intent's settled_at stays now).
    var excluded = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("30.0000"), null));
    simulator.pay(excluded.chargePublicId());
    payments.get(excluded.publicId());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE psp_simulator.charge SET updated_at = now() - interval '2 hours'"
          + " WHERE public_id = '" + excluded.chargePublicId() + "'");
    }

    String body = ingest(Instant.now().minusSeconds(3600).toString(), Instant.now().plusSeconds(60).toString());
    String reportId = com.jayway.jsonpath.JsonPath.read(body, "$.reportId");

    mockMvc.perform(get("/v1/conciliation/reports/" + reportId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_INTERNAL')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'AMOUNT_MISMATCH')]").isNotEmpty())
        .andExpect(jsonPath("$.lines[?(@.matchStatus == 'MISSING_EXTERNAL')]").isNotEmpty());
  }

  @Test
  void unknownReportIs404AndInvertedWindowIs400() throws Exception {
    mockMvc.perform(get("/v1/conciliation/reports/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T11:00:00Z\",\"to\":\"2026-09-18T10:00:00Z\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"not-an-instant\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postRequiresAnIdempotencyKey() throws Exception {
    mockMvc.perform(post("/v1/conciliation/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-18T10:00:00Z\",\"to\":\"2026-09-18T11:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Remote RED**

Push, remote `test -Dtest=ConciliationRestApiTest` → FAIL (404s/405s — no controller).

- [ ] **Step 3: Implement**

`ConciliationService.java`:
```java
package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.payments.application.PaymentsService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingest-and-match: fetches the network report and the internal settlements,
 * matches them purely, and persists report + lines immutably — one transaction.
 */
@Service
public class ConciliationService {

  private final SettlementReportSource reportSource;
  private final PaymentsService payments;
  private final ConciliationStore store;

  public ConciliationService(SettlementReportSource reportSource, PaymentsService payments,
      ConciliationStore store) {
    this.reportSource = reportSource;
    this.payments = payments;
    this.store = store;
  }

  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    var report = reportSource.fetch(from, to);
    var internal = payments.listSettlements(from, to);
    var outcome = ReportMatcher.match(report, internal);
    var summary = new SettlementReportSummary(UUID.randomUUID(), from, to,
        outcome.summary().conciled() ? "CONCILED" : "OPEN", outcome.summary().matched(),
        outcome.summary().amountMismatched(), outcome.summary().missingInternal(),
        outcome.summary().missingExternal(), Instant.now());
    store.insert(summary, outcome.lines());
    return summary;
  }
}
```

`dto/CreateReportRequest.java`:
```java
package com.leandrossb.nummus.conciliation.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Instants travel as ISO-8601 strings; parsing and from<to live in the service (400s). */
public record CreateReportRequest(
    @NotBlank(message = "from must not be blank") String from,
    @NotBlank(message = "to must not be blank") String to) {
}
```

`dto/ReportSummaryResponse.java`:
```java
package com.leandrossb.nummus.conciliation.interfaces.dto;

import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import java.time.Instant;
import java.util.UUID;

public record ReportSummaryResponse(
    UUID reportId, Instant from, Instant to, String status,
    int matched, int amountMismatched, int missingInternal, int missingExternal,
    Instant createdAt) {

  public static ReportSummaryResponse from(SettlementReportSummary summary) {
    return new ReportSummaryResponse(summary.publicId(), summary.from(), summary.to(),
        summary.status(), summary.matched(), summary.amountMismatched(),
        summary.missingInternal(), summary.missingExternal(), summary.createdAt());
  }
}
```

`dto/ReportLineResponse.java`:
```java
package com.leandrossb.nummus.conciliation.interfaces.dto;

import com.leandrossb.nummus.conciliation.application.MatchedLine;
import java.math.BigDecimal;
import java.util.UUID;

public record ReportLineResponse(
    String origin, UUID chargeId, BigDecimal reportedAmount,
    UUID internalIntentId, BigDecimal internalAmount, String matchStatus) {

  public static ReportLineResponse from(MatchedLine line) {
    return new ReportLineResponse(line.origin(), line.chargePublicId(),
        line.reportedAmount() == null ? null : line.reportedAmount().amount(),
        line.internalIntentPublicId(),
        line.internalAmount() == null ? null : line.internalAmount().amount(),
        line.matchStatus());
  }
}
```

`ConciliationReportsController.java`:
```java
package com.leandrossb.nummus.conciliation.interfaces;

import com.leandrossb.nummus.conciliation.application.ConciliationService;
import com.leandrossb.nummus.conciliation.application.UnknownConciliationReportException;
import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.conciliation.interfaces.dto.CreateReportRequest;
import com.leandrossb.nummus.conciliation.interfaces.dto.ReportLineResponse;
import com.leandrossb.nummus.conciliation.interfaces.dto.ReportSummaryResponse;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/conciliation/reports")
class ConciliationReportsController {

  private final ConciliationService conciliation;
  private final ConciliationStore store;

  ConciliationReportsController(ConciliationService conciliation, ConciliationStore store) {
    this.conciliation = conciliation;
    this.store = store;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<ReportSummaryResponse> create(@Valid @RequestBody CreateReportRequest request) {
    var summary = conciliation.ingest(Instant.parse(request.from()), Instant.parse(request.to()));
    return ResponseEntity
        .created(URI.create("/v1/conciliation/reports/" + summary.publicId()))
        .body(ReportSummaryResponse.from(summary));
  }

  @GetMapping
  List<ReportSummaryResponse> list() {
    return store.listSummaries(50).stream().map(ReportSummaryResponse::from).toList();
  }

  @GetMapping("/{id}")
  ReportDetailResponse get(@PathVariable UUID id) {
    var summary = store.findSummary(id).orElseThrow(() -> new UnknownConciliationReportException(id));
    return new ReportDetailResponse(ReportSummaryResponse.from(summary),
        store.findLines(id).stream().map(ReportLineResponse::from).toList());
  }

  record ReportDetailResponse(ReportSummaryResponse report, List<ReportLineResponse> lines) {
  }
}
```

`GlobalExceptionHandler` — two additions:
1. Import `com.leandrossb.nummus.conciliation.application.DuplicateSettlementLinesException` and `com.leandrossb.nummus.conciliation.application.UnknownConciliationReportException`; add the latter to the existing `notFound` group.
2. New entry (next to `idempotencyReuse`):
```java
  @ExceptionHandler(DuplicateSettlementLinesException.class)
  ProblemDetail duplicateSettlementLines(DuplicateSettlementLinesException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
```

- [ ] **Step 4: Remote GREEN + full verify**

Remote `test -Dtest=ConciliationRestApiTest` → PASS (4). Remote `verify` → `BUILD SUCCESS`, 180 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ \
  src/test/java/com/leandrossb/nummus/conciliation/ConciliationRestApiTest.java
git commit -m "feat: ingest and expose conciliation reports over REST

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: ArchUnit rule for the conciliation module boundary

**Files:**
- Modify: `src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: the file's existing rule idioms.
- Produces: rule `conciliationTouchesOnlyModuleApis` — `conciliation` may depend on `payments.application` and `psp_simulator.application` only; banned: `payments.{domain,infrastructure,interfaces}`, `psp_simulator.{domain,infrastructure,interfaces}`, `accounts`, `ledger`, `webhooks`.

- [ ] **Step 1: Add the rule** (append inside `ModuleBoundaryTest`)

```java
  @ArchTest
  static final ArchRule conciliationTouchesOnlyModuleApis =
      noClasses().that().resideInAPackage("..conciliation..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..psp_simulator.domain..",
              "..psp_simulator.infrastructure..", "..psp_simulator.interfaces..",
              "..accounts..", "..webhooks..");
```

(`ledger.domain.Money` is the shared kernel and stays allowed — the same carve-out as the webhooks and simulator rules.)

- [ ] **Step 2: Remote run**

Push, remote `test -Dtest=ModuleBoundaryTest` → PASS (10 rules: 9 + this one). If the rule fails, the violating dependency is a real finding — report, do not weaken.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java
git commit -m "test: ban conciliation from reaching past module APIs

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md`
- Modify: `docs/m2-backlog.md`

- [ ] **Step 1: Remote full verify**

Remote `verify` → `BUILD SUCCESS`, `Tests run: 181, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 2: Update `README.md`**

```markdown
- [x] M6 — Conciliation reports
```

- [ ] **Step 3: Append to `docs/m2-backlog.md`** (new section at end)

```markdown
## From the M6 review

M6 delivered settlement-report conciliation: the simulator emits reports,
a pure matcher classifies MATCHED / AMOUNT_MISMATCH / MISSING_INTERNAL /
MISSING_EXTERNAL, and reports persist write-once (no update grant —
corrections are new reports). Known bounds, deliberate:

- **Matching is at ingest only.** Reports are frozen verdicts; internal data
  is immutable post-settle, so a re-match could only change the verdict by
  changing the report's window — which is a new report.
- **No fees.** Report lines carry the settled amount only; fee schedules,
  REVENUE accounts, and rounding remain deferred (M3's deferral carries on).
- **Window boundaries rely on timestamps that are moved by DB-side rewrites
  in tests only**; production `settled_at` and the simulator's `updated_at`
  use the DB clock via `now()` — consistent, but a future real PSP adapter
  must define its own settlement-timestamp contract.
- **Listings are unpaginated** (50 most recent) and unauthenticated, like
  every other endpoint until merchant auth lands.
- **No scheduled reconciliation or divergence alerting** — ingest is manual
  (POST). A webhook-on-divergence is the natural follow-up once operators
  want push instead of pull.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/m2-backlog.md
git commit -m "docs: mark M6 conciliation reports complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 5: Report**

Report the remote verify summary line and test count verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V9 schema + write-once grants (+ negative UPDATE pin) | 1 |
| Payments internal API: `listSettlements` window query | 2 |
| Simulator report emission (store query, service, operator REST) | 3 |
| Pure matcher: 4 states, scale-insensitive compare, duplicate rejection | 4 |
| Store (write-once insert + reads) + report source adapter | 5 |
| Ingest service + REST (idempotent POST, GET list/detail) + error vocabulary | 6 |
| ArchUnit boundary for conciliation | 7 |
| Success criteria, README, backlog | 8 |
