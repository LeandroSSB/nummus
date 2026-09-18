# M2 Accounts and REST Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the `accounts` bounded module (payment accounts wrapping one LIABILITY ledger account each) and the merchant-facing REST skeleton with full lifecycle, natural-sign balances, and an RFC 7807 error model.

**Architecture:** Modular monolith, one Maven deployable. `accounts` follows M1's hexagonal layout (domain pure / application ports / infrastructure outbound / interfaces inbound REST) and talks to the ledger **only** through the `Ledger` port. Module boundaries are machine-checked with ArchUnit. Spec: `docs/superpowers/specs/2026-09-17-m2-accounts-rest-design.md`.

**Tech Stack:** Java 25 · Spring Boot 4.1.1 (`spring-boot-starter-web`, `spring-boot-starter-validation` added in Task 6) · JdbcClient · Flyway V4 · ArchUnit `archunit-junit5` · JUnit 5 · Testcontainers 2.x (`postgres:18-alpine`) · MockMvc.

## Global Constraints

- **Language:** English everywhere — code, identifiers, comments, commit messages. Public product repo: never demo/portfolio framing. No placeholder code.
- **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`); every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Money:** `BigDecimal` with explicit currency, never `double`/`float`; compare with `compareTo`, never `equals`/`BigDecimal.equals`. BRL only.
- **Module boundary:** `accounts` touches `ledger` only via `..ledger.application..` (the `Ledger` port) plus the `..ledger.domain..` types its signatures expose; never `..ledger.infrastructure..`; never table `ledger.*` from accounts SQL. Linkage is by public UUID only.
- **Journal untouched:** M1's migrations, triggers, and tables are never modified. M2 adds exactly one new migration (`V4__accounts_schema.sql`).
- **Bean-ordering (M1 lesson):** tasks run in numeric order — the `@Repository` (Task 4) lands before the `@Service` that consumes it (Task 5), so every `@SpringBootTest` context stays green at every commit.
- **Transactions:** every service method that writes more than one thing (accounts row + ledger call) is `@Transactional` (M1 lesson: deferred trigger + dual-write demand it).
- **Test environment (this host):** Docker runs on the remote `megalan` server via a controller-managed SSH tunnel. EVERY `./mvnw` command that runs tests MUST be prefixed with:
  ```
  DOCKER_HOST=unix:///tmp/megalan-docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.0.210 TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test ...
  ```
  Do not touch the tunnel. Health check: `curl -s --unix-socket /tmp/megalan-docker.sock http://localhost/_ping` → `OK`. If it fails, report BLOCKED. `postgres:18-alpine` is already pulled remotely. No local docker CLI exists.
- **JDK:** if `./mvnw` reports a JDK error, prefix `JAVA_HOME=/home/legat/.jdks/jdk-25.0.4.1+1`.
- **Testcontainers 2.x:** `PostgreSQLContainer` lives at `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic class).
- **Test classes end in `Test`**; success criteria = `./mvnw verify` green at the end (Task 8).
- **`@BeforeAll` needing `appConnection()`** (M1 lesson): non-static `@BeforeAll` + `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` so the Spring context (and Flyway) loads first.

## File Map (final state after all tasks)

```
pom.xml                                             (Tasks 1, 6 — dependencies)
src/main/resources/db/migration/V4__accounts_schema.sql   (Task 3)
src/main/java/com/leandrossb/nummus/ledger/application/
  Ledger.java                                        (Task 2 — add unfreezeAccount, getAccount)
  LedgerServiceImpl.java                             (Task 2 — implement both)
src/main/java/com/leandrossb/nummus/accounts/
  domain/PaymentAccount.java, AccountStatus.java,
        OpenAccountCommand.java, UnknownPaymentAccountException.java,
        PaymentAccountNotActiveException.java        (Task 4)
  application/AccountsService.java, AccountsRepository.java   (Tasks 4, 5)
  application/AccountsServiceImpl.java               (Task 5)
  infrastructure/JdbcClientAccountsRepository.java   (Task 4)
  interfaces/AccountsController.java                 (Task 6)
  interfaces/GlobalExceptionHandler.java             (Task 7)
  interfaces/dto/OpenAccountRequest.java, AccountResponse.java,
        BalanceResponse.java, StatementResponse.java (Task 6)
src/test/java/com/leandrossb/nummus/
  architecture/ModuleBoundaryTest.java               (Task 1)
  ledger/application/LedgerServiceAccountsTest.java  (Task 2 — add tests)
  ledger/LedgerServiceIntegrationTest.java           (Task 2 — add test)
  accounts/AccountsSchemaTest.java, AccountsRolesTest.java   (Task 3)
  accounts/AccountsRepositoryTest.java              (Task 4)
  ledger/application/InMemoryLedgerRepository.java  (Task 5 — make public)
  accounts/application/InMemoryAccountsRepository.java (Task 5)
  accounts/application/AccountsServiceImplTest.java  (Task 5)
  accounts/AccountsRestApiTest.java                  (Task 6)
  accounts/GlobalExceptionHandlerTest.java           (Task 7)
README.md, docs/m2-backlog.md                        (Task 8)
```

---

### Task 1: ArchUnit boundary guard

**Files:**
- Modify: `pom.xml` (test dependency)
- Create: `src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: existing production classes (M1).
- Produces: machine-checked boundary rules that guard every later task. Rules over `..accounts..` pass vacuously until the package exists — that is expected and correct.

- [ ] **Step 1: Add the ArchUnit dependency to `pom.xml`**

In `<dependencies>`, after the `testcontainers` dependency:

```xml
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>1.4.1</version>
      <scope>test</scope>
    </dependency>
```

Note: ArchUnit is NOT managed by the Spring Boot BOM — the explicit version is required. If 1.4.1 fails to resolve, use the latest 1.x on Maven Central; do not jump to 2.x without checking API compatibility.

- [ ] **Step 2: Write the boundary test**

```java
package com.leandrossb.nummus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Machine-checked module boundaries of the modular monolith. Production
 * classes only — tests may use anything. Until the accounts module exists
 * (later tasks), its rules pass vacuously.
 */
@AnalyzeClasses(packages = "com.leandrossb.nummus", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

  @ArchTest
  static final ArchRule accountsNeverTouchLedgerInfrastructure =
      noClasses().that().resideInAPackage("..accounts..")
          .should().dependOnClassesThat().resideInAPackage("..ledger.infrastructure..")
          .allowEmptyShould(true); // accounts does not exist yet in M1; enforces from Task 4 on

  @ArchTest
  static final ArchRule ledgerNeverTouchesAccounts =
      noClasses().that().resideInAPackage("..ledger..")
          .should().dependOnClassesThat().resideInAPackage("..accounts..");

  @ArchTest
  static final ArchRule domainPackagesStayFrameworkFree =
      noClasses().that().resideInAnyPackage("..ledger.domain..", "..accounts.domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "java.sql..", "jakarta.persistence..");

  @ArchTest
  static final ArchRule persistenceTypesOnlyInInfrastructure =
      noClasses().that().resideOutsideOfPackage("..infrastructure..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "java.sql..");
}
```

- [ ] **Step 3: Run it (focused, then full suite)**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=ModuleBoundaryTest
<ENV_PREFIX> ./mvnw test
```

Expected: PASS — 4 architecture rules hold on the M1 codebase (ledger domain is pure; no accounts package exists yet, so its two rules check empty sets). Full suite stays green (52/52).

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/java/com/leandrossb/nummus/architecture/
git commit -m "test: add ArchUnit module boundary guard

Codifies the modular-monolith rules: ledger and accounts only meet
through the Ledger port, domain packages stay framework-free, and
persistence types never leave infrastructure.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: Ledger port evolution — `unfreezeAccount` and `getAccount` (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/ledger/application/Ledger.java`
- Modify: `src/main/java/com/leandrossb/nummus/ledger/application/LedgerServiceImpl.java`
- Modify: `src/test/java/com/leandrossb/nummus/ledger/application/LedgerServiceAccountsTest.java`
- Modify: `src/test/java/com/leandrossb/nummus/ledger/LedgerServiceIntegrationTest.java`

**Interfaces:**
- Consumes: M1 `Ledger` port, `LedgerServiceImpl.transitionStatus` private helper, `requireAccount` private helper.
- Produces (used by Tasks 5-7): `LedgerAccount unfreezeAccount(UUID publicId)` — FROZEN→ACTIVE, CLOSED terminal; `LedgerAccount getAccount(UUID publicId)` — throws `UnknownAccountException`.

- [ ] **Step 1: Add the failing unit tests to `LedgerServiceAccountsTest`**

Append inside the class (add import `com.leandrossb.nummus.ledger.domain.AccountStatus` if not present — it already exists there from M1):

```java
  @Test
  void unfreezeRestoresActiveAndClosedIsTerminal() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    ledger.freezeAccount(account.publicId());
    assertEquals(AccountStatus.ACTIVE, ledger.unfreezeAccount(account.publicId()).status());
    ledger.closeAccount(account.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.unfreezeAccount(account.publicId()));
    assertThrows(UnknownAccountException.class, () -> ledger.unfreezeAccount(UUID.randomUUID()));
  }

  @Test
  void getAccountReturnsLedgerAccountOrThrows() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(account.publicId(), ledger.getAccount(account.publicId()).publicId());
    assertEquals(AccountType.ASSET, ledger.getAccount(account.publicId()).type());
    assertThrows(UnknownAccountException.class, () -> ledger.getAccount(UUID.randomUUID()));
  }
```

- [ ] **Step 2: Run to verify compilation fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=LedgerServiceAccountsTest
```

Expected: COMPILATION ERROR — `unfreezeAccount`/`getAccount` do not exist on `Ledger`.

- [ ] **Step 3: Implement**

In `Ledger.java`, after `closeAccount`:

```java
  /** Restores a FROZEN account to ACTIVE. CLOSED is terminal. */
  LedgerAccount unfreezeAccount(UUID publicId);

  /** The ledger account by public id. */
  LedgerAccount getAccount(UUID publicId);
```

In `LedgerServiceImpl.java`, after `closeAccount`:

```java
  @Override
  @Transactional
  public LedgerAccount unfreezeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.ACTIVE, null);
  }

  @Override
  @Transactional(readOnly = true)
  public LedgerAccount getAccount(UUID publicId) {
    return requireAccount(publicId);
  }
```

(`transitionStatus` already throws `AccountNotActiveException` when the current status is CLOSED — the terminal rule holds for unfreeze too.)

- [ ] **Step 4: Run the unit tests to verify they pass**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=LedgerServiceAccountsTest
```

Expected: PASS (8 tests — M1's 6 plus these 2).

- [ ] **Step 5: Add the integration test to `LedgerServiceIntegrationTest`**

Append inside the class (add import `com.leandrossb.nummus.ledger.domain.AccountStatus` — it is NOT currently imported there):

```java
  @Test
  void unfreezeAndAccountLookupWorkAgainstRealDatabase() {
    var asset = ledger.openAccount(new OpenAccountCommand("unfreeze cash", AccountType.ASSET, BRL));
    ledger.freezeAccount(asset.publicId());
    var unfrozen = ledger.unfreezeAccount(asset.publicId());
    assertEquals(AccountStatus.ACTIVE, unfrozen.status());

    var lookedUp = ledger.getAccount(asset.publicId());
    assertEquals(AccountType.ASSET, lookedUp.type());
    assertThrows(UnknownAccountException.class, () -> ledger.getAccount(UUID.randomUUID()));
  }
```

- [ ] **Step 6: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=LedgerServiceIntegrationTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (5 tests — 4 from M1 plus this one); full suite green (59/59).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/application/ src/test/java/com/leandrossb/nummus/ledger/
git commit -m "feat: add unfreeze and account lookup to the ledger port

unfreezeAccount restores FROZEN accounts with CLOSED terminal;
getAccount exposes the backing account the accounts module needs
for natural-sign presentation.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: Migration V4 — accounts schema with least privilege (TDD)

**Files:**
- Create: `src/main/resources/db/migration/V4__accounts_schema.sql`
- Create: `src/test/java/com/leandrossb/nummus/accounts/AccountsSchemaTest.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/AccountsRolesTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase` (admin/app connections).
- Produces: schema `accounts` with `accounts.payment_account`; app role holds SELECT/INSERT, column-level UPDATE(status, closed_at), sequence USAGE. Tasks 4-6 read/write this table.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsPaymentAccountRowsWithDefaults() throws Exception {
    String publicId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO accounts.payment_account (public_id, holder_name, ledger_account_public_id) "
          + "VALUES ('" + publicId + "', 'schema test', '" + UUID.randomUUID() + "')");
      try (ResultSet rs = st.executeQuery(
          "SELECT status, opened_at, closed_at FROM accounts.payment_account WHERE public_id = '" + publicId + "'")) {
        assertTrue(rs.next());
        assertEquals("ACTIVE", rs.getString(1));
        assertTrue(rs.getTimestamp(2) != null);
        assertTrue(rs.getTimestamp(3) == null);
      }
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsSchemaTest
```

Expected: FAIL with `schema "accounts" does not exist` (context starts; Flyway has no V4 yet).

- [ ] **Step 3: Write `V4__accounts_schema.sql`**

```sql
-- M2 accounts: module-owned schema. A payment account wraps exactly one
-- backing ledger account, referenced by public UUID only — internal ids
-- never cross modules. Unlike the journal, this table legitimately mutates
-- (status transitions), so there are no immutability triggers here.

create schema accounts;

create table accounts.payment_account (
  id                        bigint generated always as identity primary key,
  public_id                 uuid not null default gen_random_uuid() unique,
  holder_name               text not null check (holder_name <> ''),
  status                    text not null default 'ACTIVE'
                            check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
  ledger_account_public_id  uuid not null unique,
  opened_at                 timestamptz not null default now(),
  closed_at                 timestamptz
);

create index payment_account_holder_idx on accounts.payment_account (holder_name);

-- Same least-privilege pattern as V3, applied to this module's schema.
grant usage on schema accounts to nummus_app;
grant select, insert on accounts.payment_account to nummus_app;
grant update (status, closed_at) on accounts.payment_account to nummus_app;
grant usage on all sequences in schema accounts to nummus_app;
```

- [ ] **Step 4: Run to verify it passes**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsSchemaTest
```

Expected: PASS.

- [ ] **Step 5: Write the roles test (least-privilege probe)**

```java
package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * PER_CLASS so the Spring context (and Flyway's V4) is up before the
 * non-static @BeforeAll grants the app role a login — same reason as
 * LedgerRolesTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanInsertReadAndUpdateStatusButNotMutateHolderData() throws Exception {
    String publicId = UUID.randomUUID().toString();
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      st.executeUpdate("INSERT INTO accounts.payment_account (public_id, holder_name, ledger_account_public_id) "
          + "VALUES ('" + publicId + "', 'roles test', '" + UUID.randomUUID() + "')");
      st.executeUpdate("UPDATE accounts.payment_account SET status = 'FROZEN' WHERE public_id = '" + publicId + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM accounts.payment_account WHERE public_id = '" + publicId + "'")) {
        assertTrue(rs.next());
        assertTrue("FROZEN".equals(rs.getString(1)));
      }
      assertDenied(st, "UPDATE accounts.payment_account SET holder_name = 'renamed' WHERE public_id = '" + publicId + "'");
      assertDenied(st, "UPDATE accounts.payment_account SET ledger_account_public_id = '" + UUID.randomUUID()
          + "' WHERE public_id = '" + publicId + "'");
      assertDenied(st, "DELETE FROM accounts.payment_account WHERE public_id = '" + publicId + "'");
    }
  }

  private void assertDenied(Statement st, String sql) throws SQLException {
    try {
      st.executeUpdate(sql);
      fail("expected permission denial for: " + sql);
    } catch (SQLException e) {
      assertTrue(e.getMessage().contains("permission denied"),
          "expected 'permission denied' but got: " + e.getMessage());
    }
  }
}
```

- [ ] **Step 6: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest='AccountsSchemaTest,AccountsRolesTest'
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (2 tests); full suite green (61/61).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V4__accounts_schema.sql src/test/java/com/leandrossb/nummus/accounts/
git commit -m "feat: add accounts schema migration

Payment accounts linked to backing ledger accounts by public UUID,
with the same least-privilege grants the ledger schema carries.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Accounts domain, repository port, and JdbcClient adapter (TDD)

**Files:**
- Create in `src/main/java/com/leandrossb/nummus/accounts/domain/`: `PaymentAccount.java`, `AccountStatus.java`, `OpenAccountCommand.java`, `UnknownPaymentAccountException.java`, `PaymentAccountNotActiveException.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsRepository.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/infrastructure/JdbcClientAccountsRepository.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/AccountsRepositoryTest.java`

**Interfaces:**
- Consumes: V4 schema (Task 3), M1 `AccountType`/`AccountStatus`-style vocabulary patterns.
- Produces (used by Tasks 5-7):
  - `record PaymentAccount(UUID publicId, String holderName, AccountStatus status, Instant openedAt, Instant closedAt, UUID ledgerAccountPublicId)`
  - `enum AccountStatus { ACTIVE, FROZEN, CLOSED }` (accounts-owned)
  - `record OpenAccountCommand(String holderName)`
  - `UnknownPaymentAccountException(UUID publicId)`, `PaymentAccountNotActiveException(UUID publicId, AccountStatus status)` — both `extends RuntimeException`
  - `interface AccountsRepository { PaymentAccount insert(PaymentAccount); Optional<PaymentAccount> findByPublicId(UUID); boolean updateStatus(UUID, AccountStatus, Instant closedAt); }`
  - `@Repository class JdbcClientAccountsRepository implements AccountsRepository`, constructor `(JdbcClient jdbc)`

- [ ] **Step 1: Write the failing integration test**

```java
package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsRepository;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountsRepositoryTest extends IntegrationTestBase {

  @Autowired
  private AccountsRepository repository;

  private PaymentAccount newAccount(String holderName) {
    return new PaymentAccount(UUID.randomUUID(), holderName, AccountStatus.ACTIVE,
        Instant.now(), null, UUID.randomUUID());
  }

  @Test
  void insertAndFindByPublicIdRoundTrip() {
    var account = newAccount("merchant one");
    repository.insert(account);

    var found = repository.findByPublicId(account.publicId()).orElseThrow();
    assertEquals(account.publicId(), found.publicId());
    assertEquals("merchant one", found.holderName());
    assertEquals(AccountStatus.ACTIVE, found.status());
    assertEquals(account.ledgerAccountPublicId(), found.ledgerAccountPublicId());
    assertEquals(found.openedAt().truncatedTo(ChronoUnit.MILLIS),
        account.openedAt().truncatedTo(ChronoUnit.MILLIS));
    assertTrue(found.closedAt() == null);
  }

  @Test
  void findByPublicIdReturnsEmptyForUnknownId() {
    assertTrue(repository.findByPublicId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void updateStatusTransitionsAndRecordsCloseTime() {
    var account = newAccount("transient");
    repository.insert(account);

    assertTrue(repository.updateStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repository.findByPublicId(account.publicId()).orElseThrow().status());

    Instant closedAt = Instant.now();
    assertTrue(repository.updateStatus(account.publicId(), AccountStatus.CLOSED, closedAt));
    var closed = repository.findByPublicId(account.publicId()).orElseThrow();
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertEquals(closedAt.truncatedTo(ChronoUnit.MILLIS), closed.closedAt().truncatedTo(ChronoUnit.MILLIS));

    assertTrue(!repository.updateStatus(UUID.randomUUID(), AccountStatus.FROZEN, null));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsRepositoryTest
```

Expected: FAIL — no bean implements `AccountsRepository` (context startup error).

- [ ] **Step 3: Implement the domain types**

`AccountStatus.java`:

```java
package com.leandrossb.nummus.accounts.domain;

/** Payment account lifecycle: CLOSED is terminal; FROZEN and ACTIVE are reversible. */
public enum AccountStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
```

`PaymentAccount.java`:

```java
package com.leandrossb.nummus.accounts.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment account held on behalf of a holder. Wraps exactly one backing
 * ledger account (type LIABILITY), referenced by public UUID — the accounts
 * module never sees ledger internals.
 */
public record PaymentAccount(
    UUID publicId,
    String holderName,
    AccountStatus status,
    Instant openedAt,
    Instant closedAt,
    UUID ledgerAccountPublicId) {
}
```

`OpenAccountCommand.java`:

```java
package com.leandrossb.nummus.accounts.domain;

/** Command to open a new payment account. */
public record OpenAccountCommand(String holderName) {
}
```

`UnknownPaymentAccountException.java`:

```java
package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/** Thrown when a payment account id does not exist. */
public class UnknownPaymentAccountException extends RuntimeException {

  public UnknownPaymentAccountException(UUID publicId) {
    super("unknown payment account: " + publicId);
  }
}
```

`PaymentAccountNotActiveException.java`:

```java
package com.leandrossb.nummus.accounts.domain;

import java.util.UUID;

/** Thrown when an operation requires a non-terminal payment account but the account is CLOSED. */
public class PaymentAccountNotActiveException extends RuntimeException {

  public PaymentAccountNotActiveException(UUID publicId, AccountStatus status) {
    super("payment account " + publicId + " is CLOSED: " + status);
  }
}
```

`AccountsRepository.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the accounts module. Status transitions update only status and closed_at. */
public interface AccountsRepository {

  PaymentAccount insert(PaymentAccount account);

  Optional<PaymentAccount> findByPublicId(UUID publicId);

  /** @return false when the account does not exist. */
  boolean updateStatus(UUID publicId, AccountStatus status, Instant closedAt);
}
```

`JdbcClientAccountsRepository.java`:

```java
package com.leandrossb.nummus.accounts.infrastructure;

import com.leandrossb.nummus.accounts.application.AccountsRepository;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientAccountsRepository implements AccountsRepository {

  private final JdbcClient jdbc;

  public JdbcClientAccountsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PaymentAccount insert(PaymentAccount account) {
    jdbc.sql("""
        insert into accounts.payment_account
          (public_id, holder_name, status, ledger_account_public_id, opened_at, closed_at)
        values (:publicId, :holderName, :status, :ledgerAccountPublicId, :openedAt, :closedAt)
        """)
        .param("publicId", account.publicId())
        .param("holderName", account.holderName())
        .param("status", account.status().name())
        .param("ledgerAccountPublicId", account.ledgerAccountPublicId())
        .param("openedAt", toOffsetDateTime(account.openedAt()))
        .param("closedAt", account.closedAt() == null ? null : toOffsetDateTime(account.closedAt()))
        .update();
    return account;
  }

  @Override
  public Optional<PaymentAccount> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, holder_name, status, ledger_account_public_id, opened_at, closed_at
        from accounts.payment_account where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapPaymentAccount(rs))
        .optional();
  }

  @Override
  public boolean updateStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    int updated = jdbc.sql("""
        update accounts.payment_account set status = :status, closed_at = :closedAt
        where public_id = :publicId
        """)
        .param("status", status.name())
        .param("closedAt", closedAt == null ? null : toOffsetDateTime(closedAt))
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private PaymentAccount mapPaymentAccount(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new PaymentAccount(
        rs.getObject("public_id", UUID.class),
        rs.getString("holder_name"),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant(),
        rs.getObject("ledger_account_public_id", UUID.class));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
```

- [ ] **Step 4: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsRepositoryTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (3 tests); full suite green (64/64). ArchUnit rules still pass (repository lives in `..accounts.infrastructure..`; domain has zero Spring imports).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/ src/test/java/com/leandrossb/nummus/accounts/AccountsRepositoryTest.java
git commit -m "feat: add accounts domain and JdbcClient repository

Payment account records, the accounts persistence port, and its
PostgreSQL adapter over the V4 schema.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: `AccountsServiceImpl` with lifecycle and natural sign (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/application/InMemoryAccountsRepository.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/application/AccountsServiceImplTest.java`
- Modify: `src/test/java/com/leandrossb/nummus/ledger/application/InMemoryLedgerRepository.java` (package-private → public)

**Interfaces:**
- Consumes: `AccountsRepository` + domain types (Task 4), `Ledger` port incl. `unfreezeAccount`/`getAccount` (Task 2).
- Produces (used by Tasks 6-7):
  - `interface AccountsService { PaymentAccount open(OpenAccountCommand); PaymentAccount get(UUID); PaymentAccount freeze(UUID); PaymentAccount unfreeze(UUID); PaymentAccount close(UUID); Money balance(UUID); AccountStatement statement(UUID, Page); }`
  - `@Service class AccountsServiceImpl implements AccountsService`, constructor `(Ledger ledger, AccountsRepository repository)`. Balances/statement balance figures are **natural-signed** (credit-normal backing ⇒ negated); statement lines unchanged.
  - Test-scope: `public class InMemoryAccountsRepository implements AccountsRepository`; M1's `InMemoryLedgerRepository` becomes `public` (accounts tests live in another package).

- [ ] **Step 1: Make M1's ledger fake public**

In `src/test/java/com/leandrossb/nummus/ledger/application/InMemoryLedgerRepository.java` change:

```java
class InMemoryLedgerRepository implements LedgerRepository {
```

to:

```java
public class InMemoryLedgerRepository implements LedgerRepository {
```

(and the class javadoc line above it stays). Accounts unit tests construct a real `LedgerServiceImpl` over this fake; the accounts package cannot see it while package-private.

- [ ] **Step 2: Write the failing tests**

`InMemoryAccountsRepository.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake for accounts service unit tests. */
public class InMemoryAccountsRepository implements AccountsRepository {

  private final Map<UUID, PaymentAccount> accounts = new ConcurrentHashMap<>();

  @Override
  public PaymentAccount insert(PaymentAccount account) {
    accounts.put(account.publicId(), account);
    return account;
  }

  @Override
  public Optional<PaymentAccount> findByPublicId(UUID publicId) {
    return Optional.ofNullable(accounts.get(publicId));
  }

  @Override
  public boolean updateStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    var current = accounts.get(publicId);
    if (current == null) {
      return false;
    }
    accounts.put(publicId, new PaymentAccount(current.publicId(), current.holderName(),
        status, current.openedAt(), closedAt, current.ledgerAccountPublicId()));
    return true;
  }
}
```

`AccountsServiceImplTest.java`:

```java
package com.leandrossb.nummus.accounts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.InMemoryLedgerRepository;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.LedgerServiceImpl;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsServiceImplTest {

  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository());

  @Test
  void openCreatesActiveAccountWithBackingLiabilityLedgerAccount() {
    var account = accounts.open(new OpenAccountCommand("  Merchant One  "));
    assertNotNull(account.publicId());
    assertEquals("Merchant One", account.holderName());
    assertEquals(AccountStatus.ACTIVE, account.status());
    assertNull(account.closedAt());
    var backing = ledger.getAccount(account.ledgerAccountPublicId());
    assertEquals(AccountType.LIABILITY, backing.type());
    assertTrue(backing.name().startsWith("payable "));
  }

  @Test
  void openRejectsBlankAndOversizedNames() {
    assertThrows(IllegalArgumentException.class, () -> accounts.open(new OpenAccountCommand(null)));
    assertThrows(IllegalArgumentException.class, () -> accounts.open(new OpenAccountCommand("   ")));
    assertThrows(IllegalArgumentException.class,
        () -> accounts.open(new OpenAccountCommand("x".repeat(201))));
  }

  @Test
  void freezeUnfreezeCloseLifecycleWithTerminalClosed() {
    var account = accounts.open(new OpenAccountCommand("m"));
    assertEquals(AccountStatus.FROZEN, accounts.freeze(account.publicId()).status());
    assertEquals(AccountStatus.ACTIVE, accounts.unfreeze(account.publicId()).status());
    var closed = accounts.close(account.publicId());
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertNotNull(closed.closedAt());

    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.freeze(account.publicId()));
    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.unfreeze(account.publicId()));
    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.close(account.publicId()));
  }

  @Test
  void unfreezeOnActiveAccountIsRejected() {
    var account = accounts.open(new OpenAccountCommand("m"));
    assertThrows(IllegalArgumentException.class, () -> accounts.unfreeze(account.publicId()));
  }

  @Test
  void ledgerAccountStatusMirrorsPaymentAccountStatus() {
    var account = accounts.open(new OpenAccountCommand("m"));
    accounts.freeze(account.publicId());
    var frozenBacking = ledger.getAccount(account.ledgerAccountPublicId());
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.FROZEN, frozenBacking.status());
    accounts.unfreeze(account.publicId());
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.ACTIVE,
        ledger.getAccount(account.ledgerAccountPublicId()).status());
  }

  @Test
  void balancePresentsNaturalSignForCreditNormalBackingAccount() {
    var account = accounts.open(new OpenAccountCommand("m"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new com.leandrossb.nummus.ledger.application.PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    assertEquals(0, accounts.balance(account.publicId()).compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void statementBalanceIsNaturalSignedWhileLinesStayAsPosted() {
    var account = accounts.open(new OpenAccountCommand("m"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new com.leandrossb.nummus.ledger.application.PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    var statement = accounts.statement(account.publicId(), new Page(0, 10));
    assertEquals(0, statement.balance().compareTo(Money.ofBrl("150.0000")));
    assertEquals(1, statement.lines().size());
    assertEquals(Direction.CREDIT, statement.lines().get(0).direction());
    assertEquals(0, statement.lines().get(0).amount().compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void unknownIdsThrowUnknownPaymentAccount() {
    var id = UUID.randomUUID();
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.get(id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.freeze(id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.balance(id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.statement(id, new Page(0, 10)));
  }

  @Test
  void getRoundTripsOpenedAccount() {
    PaymentAccount account = accounts.open(new OpenAccountCommand("m"));
    PaymentAccount found = accounts.get(account.publicId());
    assertEquals(account.publicId(), found.publicId());
    assertEquals(account.ledgerAccountPublicId(), found.ledgerAccountPublicId());
  }
}
```

- [ ] **Step 3: Run to verify compilation fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsServiceImplTest
```

Expected: COMPILATION ERROR — `AccountsService`/`AccountsServiceImpl` do not exist.

- [ ] **Step 4: Implement**

`AccountsService.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import java.util.UUID;

/**
 * The accounts module's internal API. Every payment account wraps exactly one
 * LIABILITY ledger account; balances and statements are derived by the ledger
 * and presented with the holder's natural sign.
 */
public interface AccountsService {

  PaymentAccount open(OpenAccountCommand cmd);

  PaymentAccount get(UUID publicId);

  PaymentAccount freeze(UUID publicId);

  PaymentAccount unfreeze(UUID publicId);

  PaymentAccount close(UUID publicId);

  /** Derived balance in natural sign: available funds read positive. */
  Money balance(UUID publicId);

  /** Postings newest first with the natural-signed balance; lines stay as posted. */
  AccountStatement statement(UUID publicId, Page page);
}
```

`AccountsServiceImpl.java`:

```java
package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountsServiceImpl implements AccountsService {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final int HOLDER_NAME_MAX = 200;

  private final Ledger ledger;
  private final AccountsRepository repository;

  public AccountsServiceImpl(Ledger ledger, AccountsRepository repository) {
    this.ledger = ledger;
    this.repository = repository;
  }

  @Override
  @Transactional
  public PaymentAccount open(OpenAccountCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    String holderName = cmd.holderName() == null ? "" : cmd.holderName().trim();
    if (holderName.isEmpty()) {
      throw new IllegalArgumentException("holder name must not be blank");
    }
    if (holderName.length() > HOLDER_NAME_MAX) {
      throw new IllegalArgumentException("holder name must be at most " + HOLDER_NAME_MAX + " characters");
    }
    UUID publicId = UUID.randomUUID();
    var backing = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "payable " + publicId.toString().substring(0, 8), AccountType.LIABILITY, BRL));
    return repository.insert(new PaymentAccount(publicId, holderName, AccountStatus.ACTIVE,
        Instant.now(), null, backing.publicId()));
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentAccount get(UUID publicId) {
    return require(publicId);
  }

  @Override
  @Transactional
  public PaymentAccount freeze(UUID publicId) {
    return transition(publicId, AccountStatus.FROZEN, null, ledger::freezeAccount);
  }

  @Override
  @Transactional
  public PaymentAccount unfreeze(UUID publicId) {
    var current = require(publicId);
    if (current.status() == AccountStatus.ACTIVE) {
      throw new IllegalArgumentException("payment account is not FROZEN: " + publicId);
    }
    return transition(publicId, AccountStatus.ACTIVE, null, ledger::unfreezeAccount);
  }

  @Override
  @Transactional
  public PaymentAccount close(UUID publicId) {
    return transition(publicId, AccountStatus.CLOSED, Instant.now(), ledger::closeAccount);
  }

  @Override
  @Transactional(readOnly = true)
  public Money balance(UUID publicId) {
    var account = require(publicId);
    return naturalSigned(account, ledger.balance(account.ledgerAccountPublicId()));
  }

  @Override
  @Transactional(readOnly = true)
  public AccountStatement statement(UUID publicId, Page page) {
    Objects.requireNonNull(page, "page must not be null");
    var account = require(publicId);
    var raw = ledger.statement(account.ledgerAccountPublicId(), page);
    return new AccountStatement(raw.account(), naturalSigned(account, raw.balance()), raw.lines());
  }

  private PaymentAccount require(UUID publicId) {
    return repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPaymentAccountException(publicId));
  }

  private PaymentAccount transition(UUID publicId, AccountStatus target, Instant closedAt,
      Consumer<UUID> ledgerTransition) {
    var current = require(publicId);
    if (current.status() == AccountStatus.CLOSED) {
      throw new PaymentAccountNotActiveException(publicId, current.status());
    }
    ledgerTransition.accept(current.ledgerAccountPublicId());
    repository.updateStatus(publicId, target, closedAt);
    return get(publicId);
  }

  private Money naturalSigned(PaymentAccount account, Money raw) {
    var backing = ledger.getAccount(account.ledgerAccountPublicId());
    return backing.type().normalBalance() == Direction.CREDIT
        ? Money.of(raw.amount().negate(), raw.currency())
        : raw;
  }
}
```

Note: `transition` performs the ledger call first — the port validates the terminal state authoritatively (CLOSED backing accounts throw `AccountNotActiveException`) — then updates the accounts row; both run in the one surrounding transaction.

- [ ] **Step 5: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsServiceImplTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (9 tests); full suite green (73/73). ArchUnit still green: the service imports only `ledger.application.Ledger` and ledger domain vocabulary.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/application/ src/test/java/com/leandrossb/nummus/accounts/application/ src/test/java/com/leandrossb/nummus/ledger/application/InMemoryLedgerRepository.java
git commit -m "feat: add accounts service with lifecycle and natural-sign balances

Payment account open/freeze/unfreeze/close dual-written with the
backing ledger account in one transaction; balances presented in
the holder's natural sign.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: REST endpoints and DTOs — happy paths (TDD)

**Files:**
- Modify: `pom.xml` (web + validation starters)
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/OpenAccountRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/AccountResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/BalanceResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/dto/StatementResponse.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java`

**Interfaces:**
- Consumes: `AccountsService` (Task 5).
- Produces (used by Task 7's error tests): endpoints `POST /v1/accounts`, `GET /v1/accounts/{id}`, `GET /v1/accounts/{id}/balance`, `GET /v1/accounts/{id}/statement?offset=&limit=`, `POST /v1/accounts/{id}/freeze|unfreeze|close`.

- [ ] **Step 1: Add web and validation starters to `pom.xml`**

In `<dependencies>`, after `spring-boot-starter-jdbc`:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing REST test**

```java
package com.leandrossb.nummus.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AccountsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private Ledger ledger;

  private String createAccount(String holderName) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"" + holderName + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturns201WithLocationAndAccountBody() throws Exception {
    mockMvc.perform(post("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"Merchant One\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.publicId").exists())
        .andExpect(jsonPath("$.holderName").value("Merchant One"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.closedAt").doesNotExist());
  }

  @Test
  void getReturnsAccountById() throws Exception {
    String location = createAccount("Merchant Two");
    String id = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(get(location)).andExpect(status().isOk())
        .andExpect(jsonPath("$.publicId").value(id))
        .andExpect(jsonPath("$.holderName").value("Merchant Two"));
  }

  @Test
  void lifecycleEndpointsTransitionStatus() throws Exception {
    String location = createAccount("Merchant Three");

    mockMvc.perform(post(location + "/freeze")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    mockMvc.perform(post(location + "/unfreeze")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc.perform(post(location + "/close")).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").exists());
  }

  @Test
  void balanceAndStatementPresentNaturalSignAfterLedgerFunding() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Funded Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    mockMvc.perform(get("/v1/accounts/{id}/balance", account.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(150.0000))
        .andExpect(jsonPath("$.currency").value("BRL"))
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].direction").value("CREDIT"));
  }

  @Test
  void statementPaginatesViaQueryParameters() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Busy Merchant"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "rest house asset 2", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    for (int i = 1; i <= 3; i++) {
      ledger.post(new PostTransactionCommand("stmt-" + i, List.of(
          new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
          new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
    }

    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .queryParam("offset", "1").queryParam("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines.length()").value(1))
        .andExpect(jsonPath("$.lines[0].memo").value("stmt-2"));
  }
}
```

(The JSON value assertions use Jackson's numeric comparison — `value(150.0000)` matches the serialized `BigDecimal` regardless of trailing-zero scale.)

- [ ] **Step 3: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsRestApiTest
```

Expected: the tests COMPILE (all imports exist once the pom has web + validation) but FAIL — every request hits no mapping and returns 404, so the `status().isCreated()`/`isOk()` assertions fail. That is the RED state: the endpoints do not exist.

- [ ] **Step 4: Implement the DTOs and controller**

`OpenAccountRequest.java`:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for opening a payment account. */
public record OpenAccountRequest(
    @NotBlank(message = "holderName must not be blank")
    @Size(max = 200, message = "holderName must be at most 200 characters")
    String holderName) {
}
```

`AccountResponse.java`:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.UUID;

/** REST view of a payment account. UUIDs only — internal ids never appear. */
public record AccountResponse(
    UUID publicId,
    String holderName,
    String status,
    Instant openedAt,
    Instant closedAt) {

  public static AccountResponse from(PaymentAccount account) {
    return new AccountResponse(account.publicId(), account.holderName(),
        account.status().name(), account.openedAt(), account.closedAt());
  }
}
```

`BalanceResponse.java`:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;

/** REST view of a derived balance in natural sign. */
public record BalanceResponse(BigDecimal amount, String currency) {

  public static BalanceResponse from(Money balance) {
    return new BalanceResponse(balance.amount(), balance.currency().getCurrencyCode());
  }
}
```

`StatementResponse.java`:

```java
package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST view of a statement: natural-signed balance plus postings newest first. */
public record StatementResponse(BigDecimal balance, String currency, List<Line> lines) {

  public static StatementResponse from(AccountStatement statement) {
    return new StatementResponse(statement.balance().amount(),
        statement.balance().currency().getCurrencyCode(),
        statement.lines().stream().map(Line::from).toList());
  }

  /** One posting with its originating transaction context, exactly as booked. */
  record Line(Instant bookedAt, UUID transactionPublicId, String memo, String direction,
      BigDecimal amount) {

    static Line from(com.leandrossb.nummus.ledger.domain.StatementLine line) {
      return new Line(line.bookedAt(), line.transactionPublicId(), line.memo(),
          line.direction().name(), line.amount().amount());
    }
  }
}
```

`AccountsController.java`:

```java
package com.leandrossb.nummus.accounts.interfaces;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.interfaces.dto.AccountResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.BalanceResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.OpenAccountRequest;
import com.leandrossb.nummus.accounts.interfaces.dto.StatementResponse;
import com.leandrossb.nummus.ledger.domain.Page;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountsController {

  private final AccountsService accounts;

  AccountsController(AccountsService accounts) {
    this.accounts = accounts;
  }

  @PostMapping
  ResponseEntity<AccountResponse> create(@Valid @RequestBody OpenAccountRequest request) {
    var account = accounts.open(new OpenAccountCommand(request.holderName()));
    return ResponseEntity
        .created(URI.create("/v1/accounts/" + account.publicId()))
        .body(AccountResponse.from(account));
  }

  @GetMapping("/{id}")
  AccountResponse get(@PathVariable UUID id) {
    return AccountResponse.from(accounts.get(id));
  }

  @GetMapping("/{id}/balance")
  BalanceResponse balance(@PathVariable UUID id) {
    return BalanceResponse.from(accounts.balance(id));
  }

  @GetMapping("/{id}/statement")
  StatementResponse statement(@PathVariable UUID id,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    return StatementResponse.from(accounts.statement(id, new Page(offset, limit)));
  }

  @PostMapping("/{id}/freeze")
  AccountResponse freeze(@PathVariable UUID id) {
    return AccountResponse.from(accounts.freeze(id));
  }

  @PostMapping("/{id}/unfreeze")
  AccountResponse unfreeze(@PathVariable UUID id) {
    return AccountResponse.from(accounts.unfreeze(id));
  }

  @PostMapping("/{id}/close")
  AccountResponse close(@PathVariable UUID id) {
    return AccountResponse.from(accounts.close(id));
  }
}
```

- [ ] **Step 5: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=AccountsRestApiTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (5 tests); full suite green (78/78).

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/leandrossb/nummus/accounts/interfaces/ src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java
git commit -m "feat: add REST endpoints for payment accounts

Create, get, balance, statement, and the freeze/unfreeze/close
lifecycle over /v1/accounts with validated requests and DTO views.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: RFC 7807 error model with commit-time trigger mapping (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/accounts/interfaces/GlobalExceptionHandler.java`
- Create: `src/test/java/com/leandrossb/nummus/accounts/GlobalExceptionHandlerTest.java`
- Modify: `src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java` (add error-contract tests)

**Interfaces:**
- Consumes: exceptions from both modules; V2 trigger message markers (`is unbalanced`, `non-ACTIVE`).
- Produces: `application/problem+json` responses — 404 unknown ids, 409 lifecycle conflicts and commit-time trigger failures, 400 validation/malformed input, 500 otherwise.

- [ ] **Step 1: Write the failing handler unit test**

```java
package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.accounts.interfaces.GlobalExceptionHandler;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final UUID id = UUID.randomUUID();

  @Test
  void unknownIdsMapTo404() {
    assertEquals(HttpStatus.NOT_FOUND, handler.notFound(new UnknownPaymentAccountException(id)).getStatus());
    assertEquals(HttpStatus.NOT_FOUND, handler.notFound(new UnknownAccountException(id)).getStatus());
    assertEquals(HttpStatus.NOT_FOUND, handler.notFound(new UnknownTransactionException(id)).getStatus());
  }

  @Test
  void lifecycleConflictsMapTo409() {
    PaymentAccountNotActiveException payment =
        new PaymentAccountNotActiveException(id, com.leandrossb.nummus.accounts.domain.AccountStatus.CLOSED);
    assertEquals(HttpStatus.CONFLICT, handler.conflict(payment).getStatus());
    assertEquals(HttpStatus.CONFLICT,
        handler.conflict(new AccountNotActiveException(id,
            com.leandrossb.nummus.ledger.domain.AccountStatus.CLOSED)).getStatus());
    assertEquals(HttpStatus.CONFLICT,
        handler.conflict(new TransactionAlreadyReversedException(id, null)).getStatus());
  }

  @Test
  void commitTimeTriggerFailuresMapTo409() {
    var unbalanced = new TransactionSystemException("commit",
        new RuntimeException("ERROR: transaction 42 is unbalanced: debits minus credits = 10.0000"));
    assertEquals(HttpStatus.CONFLICT, handler.commitConflict(unbalanced).getStatus());

    var frozen = new TransactionSystemException("commit",
        new RuntimeException("ERROR: transaction 42 posts to a non-ACTIVE account"));
    assertEquals(HttpStatus.CONFLICT, handler.commitConflict(frozen).getStatus());

    var frozenOutsideTx = new UncategorizedSQLException("statement", "insert", 
        new RuntimeException("ERROR: transaction 42 posts to a non-ACTIVE account"));
    assertEquals(HttpStatus.CONFLICT, handler.commitConflict(frozenOutsideTx).getStatus());
  }

  @Test
  void unrelatedCommitFailuresRethrow() {
    var unrelated = new TransactionSystemException("commit",
        new RuntimeException("connection reset by peer"));
    assertThrows(RuntimeException.class, () -> handler.commitConflict(unrelated));
  }

  @Test
  void badRequestsMapTo400() {
    assertEquals(HttpStatus.BAD_REQUEST,
        handler.badRequest(new IllegalArgumentException("holder name must not be blank")).getStatus());
  }
}
```

- [ ] **Step 2: Run to verify compilation fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=GlobalExceptionHandlerTest
```

Expected: COMPILATION ERROR — `GlobalExceptionHandler` does not exist.

- [ ] **Step 3: Implement `GlobalExceptionHandler.java`**

```java
package com.leandrossb.nummus.accounts.interfaces;

import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.InvalidMoneyException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 7807 error surface for the REST skeleton. Commit-time failures raised
 * by the database's enforcement triggers surface as 409 conflicts — the
 * deterministic variants of the same violations already fail fast in the
 * services; this catches the mid-flight race (an account frozen between
 * validation and COMMIT) and any writer that bypasses the services.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

  @ExceptionHandler({UnknownPaymentAccountException.class, UnknownAccountException.class,
      UnknownTransactionException.class})
  ProblemDetail notFound(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({PaymentAccountNotActiveException.class, AccountNotActiveException.class,
      TransactionAlreadyReversedException.class})
  ProblemDetail conflict(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, InvalidMoneyException.class,
      CurrencyMismatchException.class, MethodArgumentNotValidException.class,
      MethodArgumentTypeMismatchException.class})
  ProblemDetail badRequest(Exception e) {
    String detail = e instanceof MethodArgumentNotValidException validation
        ? validation.getBindingResult().getAllErrors().get(0).getDefaultMessage()
        : e.getMessage();
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler({TransactionSystemException.class, UncategorizedSQLException.class})
  ProblemDetail commitConflict(RuntimeException e) {
    String root = rootMessage(e);
    if (root != null && (root.contains("is unbalanced") || root.contains("non-ACTIVE"))) {
      return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
          "transaction rejected by ledger invariants: " + root);
    }
    throw e;
  }

  private static String rootMessage(Throwable t) {
    String message = t.getMessage();
    Throwable cause = t.getCause();
    int depth = 0;
    while (cause != null && depth++ < 100) {
      message = cause.getMessage();
      cause = cause.getCause();
    }
    return message;
  }
}
```

- [ ] **Step 4: Run the handler unit test**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=GlobalExceptionHandlerTest
```

Expected: PASS (5 tests).

- [ ] **Step 5: Add REST error-contract tests to `AccountsRestApiTest`**

Append inside the class:

```java
  @Test
  void unknownAccountIdReturns404Problem() throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void blankHolderNameReturns400() throws Exception {
    mockMvc.perform(post("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void malformedUuidReturns400() throws Exception {
    mockMvc.perform(get("/v1/accounts/not-a-uuid"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void lifecycleConflictOnClosedAccountReturns409() throws Exception {
    String location = createAccount("Conflict Merchant");
    mockMvc.perform(post(location + "/close")).andExpect(status().isOk());
    mockMvc.perform(post(location + "/freeze"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void paginationBeyondBoundsReturns400() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Paged Merchant"));
    mockMvc.perform(get("/v1/accounts/{id}/statement", account.publicId())
            .queryParam("limit", "501"))
        .andExpect(status().isBadRequest());
  }
```

- [ ] **Step 6: Run focused, then full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest='AccountsRestApiTest,GlobalExceptionHandlerTest'
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (10 REST + 5 handler tests); full suite green (88/88).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/accounts/interfaces/GlobalExceptionHandler.java src/test/java/com/leandrossb/nummus/accounts/
git commit -m "feat: add RFC 7807 error model for the REST skeleton

Unknown ids map to 404, lifecycle conflicts to 409, validation
failures to 400, and commit-time trigger failures to 409 so the
mid-flight freeze race never leaks an infrastructure exception.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` (M2 checkbox)
- Modify: `docs/m2-backlog.md` (strike resolved items)

**Interfaces:**
- Consumes: everything.
- Produces: the M2 success-criteria evidence.

- [ ] **Step 1: Run the full build**

```bash
<ENV_PREFIX> ./mvnw verify
```

Expected: `BUILD SUCCESS`, all tests green (88 expected). The suite must include: ArchUnit boundary rules, ledger unfreeze/lookup, accounts schema + least privilege, repository round-trips, service lifecycle + natural sign, REST happy paths and error contract.

- [ ] **Step 2: Update `README.md`**

Change the milestone line to:

```markdown
- [x] M2 — Accounts and REST API skeleton
```

(remove the backlog link from that line and delete the `([backlog notes](docs/m2-backlog.md))` suffix if the doc is retired in Step 3).

- [ ] **Step 3: Update `docs/m2-backlog.md`**

Three items are now resolved — rewrite the doc keeping only what remains. Remove the sections: the "Unfreeze API" bullet, the "commit-time exception translation" bullet under Behavior and API, and the natural-sign concern (it never had a bullet — verify none references it). Keep: `Money` comparability, scale normalization, `ALTER DEFAULT PRIVILEGES` note, test-suite polish items, the O(N²) trigger note, and the concurrency timeout note. Add at the top:

```markdown
> Updated after M2: the unfreeze API, natural-sign presentation, and
> commit-time exception translation are implemented; remaining notes stay
> recorded for later milestones.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/m2-backlog.md
git commit -m "docs: mark M2 accounts and REST skeleton complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 5: Report**

Report the final `./mvnw verify` summary line and the test count. Do not claim success without the command output.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| ArchUnit boundary rules | 1 |
| Ledger port: unfreezeAccount, getAccount | 2 |
| V4 accounts schema + least privilege | 3 |
| Domain types + repository adapter | 4 |
| Service: open/freeze/unfreeze/close, dual-write, natural sign | 5 |
| REST: create/get/balance/statement/lifecycle | 6 |
| Error model incl. commit-time trigger mapping | 7 |
| Success criteria, README, backlog | 8 |
