# M1 Ledger Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the M1 ledger core — immutable double-entry journal with database-enforced invariants, derived balances, and compensating-entry reversals — plus the Maven/Spring Boot project bootstrap it sits on.

**Architecture:** Modular monolith, hexagonal inside the `ledger` module: pure domain (`ledger.domain`), application port/service (`ledger.application`), JdbcClient + Flyway adapter (`ledger.infrastructure`). PostgreSQL schema `ledger` owned by the module; enforcement = deferred constraint trigger (balance) + immutability triggers + role separation (`nummus_app` has no UPDATE/DELETE on journal tables). Balances are always derived by SQL aggregation. Spec: `docs/superpowers/specs/2026-09-16-m1-ledger-core-design.md`.

**Tech Stack:** Java 25 · Spring Boot 4.1.1 (`spring-boot-starter-parent`) · `spring-boot-starter-jdbc` (JdbcClient) · `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` · PostgreSQL 18 (Testcontainers `postgres:18-alpine`) · JUnit 5 · Maven wrapper.

## Global Constraints

- **Language:** English everywhere — code, identifiers, comments, commit messages. Never describe the project as portfolio/demo/study work (public product repo).
- **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`, `test:`, `docs:`); every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Money:** `BigDecimal` only (never `double`/`float`), scale ≤ 4, currency BRL. Compare amounts with `compareTo`, never `BigDecimal.equals`.
- **Ledger:** append-only — entries are never updated or deleted; corrections are compensating entries; every transaction balances to zero (code fail-fast **and** database trigger).
- **Scope:** No HTTP endpoints in M1. The `Ledger` port is the module's only public contract; consumers are the tests.
- **Package root:** `com.leandrossb.nummus`. Module packages: `ledger.domain` (zero Spring/JDBC imports), `ledger.application`, `ledger.infrastructure`.
- **Test command:** `./mvnw test` runs everything (all test classes end in `Test` so Surefire picks them up). Success criteria = `./mvnw verify` green.
- **Prerequisites on this host:** JDK 25 (`java -version` shows 25.x), Docker daemon running (`docker info` succeeds) for Testcontainers, network access for dependency downloads.
- **Wrapper bootstrap note:** the spec says "one-shot Maven container"; this plan uses an equivalently one-shot Maven extracted to `/tmp` (no Docker dependency for the host, same throwaway semantics).

## File Map (final state after all tasks)

```
pom.xml
.gitattributes                                     (Task 1)
mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties  (Task 1, generated)
src/main/resources/application.yml                 (Task 1)
src/main/resources/db/migration/
  V1__ledger_schema.sql                            (Task 7)
  V2__ledger_enforcement.sql                       (Task 8)
  V3__ledger_roles.sql                             (Task 9)
src/main/java/com/leandrossb/nummus/
  Application.java                                 (Task 1)
  ledger/domain/
    Money.java, InvalidMoneyException.java, CurrencyMismatchException.java (Task 3)
    Direction.java, AccountType.java, AccountStatus.java                  (Task 4)
    Page.java, PostingDraft.java, PostedPosting.java, PostedTransaction.java,
    StatementLine.java, AccountStatement.java, LedgerAccount.java          (Task 4)
    TooFewPostingsException.java, UnbalancedTransactionException.java,
    UnknownAccountException.java, AccountNotActiveException.java,
    UnknownTransactionException.java, TransactionAlreadyReversedException.java (Task 4)
  ledger/application/
    Ledger.java, LedgerRepository.java             (Task 5)
    OpenAccountCommand.java, PostTransactionCommand.java (Task 5)
    LedgerServiceImpl.java                         (Task 6)
  ledger/infrastructure/
    JdbcClientLedgerRepository.java                (Tasks 10-11)
src/test/java/com/leandrossb/nummus/
  testutils/IntegrationTestBase.java               (Task 2)
  ApplicationSmokeTest.java                        (Task 2)
  ledger/domain/MoneyTest.java                     (Task 3)
  ledger/domain/AccountTypeTest.java, PostingDraftTest.java, PageTest.java (Task 4)
  ledger/application/InMemoryLedgerRepository.java (Task 5)
  ledger/application/LedgerServiceAccountsTest.java (Task 6)
  ledger/application/LedgerServicePostTest.java     (Task 6)
  ledger/LedgerSchemaTest.java                      (Task 7)
  ledger/LedgerEnforcementTest.java                 (Task 8)
  ledger/LedgerRolesTest.java                       (Task 9)
  ledger/LedgerRepositoryAccountsTest.java          (Task 10)
  ledger/LedgerRepositoryJournalTest.java           (Task 11)
  ledger/LedgerServiceIntegrationTest.java          (Task 12)
  ledger/LedgerConcurrencyIntegrationTest.java      (Task 13)
```

---

### Task 1: Maven skeleton, wrapper, and Spring Boot application

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/leandrossb/nummus/Application.java`
- Create: `src/main/resources/application.yml`
- Create: `.gitattributes`
- Generate: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (via one-shot Maven)

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a building project. Later tasks add files only — the coordinates, dependency list, and `Application` class below are final for M1.

- [ ] **Step 1: Verify host tooling**

```bash
java -version   # must print 25.x
docker info > /dev/null && echo "docker OK"
```

Expected: Java 25; `docker OK` (Docker needed from Task 2 on, checked now to fail early).

- [ ] **Step 2: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>com.leandrossb</groupId>
  <artifactId>nummus</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>nummus</name>
  <description>Payment account core: immutable double-entry ledger, instant payments, idempotent merchant APIs.</description>

  <properties>
    <java.version>25</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-flyway</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Note: Testcontainers versions are managed by the Spring Boot BOM. Do not add explicit version numbers.

- [ ] **Step 3: Generate the Maven wrapper with a one-shot Maven**

```bash
curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz -o /tmp/apache-maven-3.9.9-bin.tar.gz
tar -xzf /tmp/apache-maven-3.9.9-bin.tar.gz -C /tmp
/tmp/apache-maven-3.9.9/bin/mvn -N wrapper:wrapper -Dmaven=3.9.9
chmod +x mvnw
```

Expected: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` created in the repo root; `distributionUrl` in the properties file points at `apache-maven-3.9.9-bin.zip`.

- [ ] **Step 4: Write `src/main/java/com/leandrossb/nummus/Application.java`**

```java
package com.leandrossb.nummus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
```

- [ ] **Step 5: Write `src/main/resources/application.yml`**

```yaml
spring:
  application:
    name: nummus
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:nummus}
    username: ${DB_USER:nummus_app}
    password: ${DB_PASSWORD:nummus_app}
  flyway:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:nummus}
    user: ${FLYWAY_USER:nummus_owner}
    password: ${FLYWAY_PASSWORD:nummus_owner}
```

Runtime uses the least-privileged role (`nummus_app`); migrations use the owner role. Tests override all six properties (Task 2).

- [ ] **Step 6: Write `.gitattributes`**

```
mvnw text eol=lf
*.cmd text eol=crlf
```

- [ ] **Step 7: Verify the build**

```bash
./mvnw -q compile && ./mvnw -q test
```

Expected: `BUILD SUCCESS` for both (first run downloads dependencies — allow several minutes). Zero tests run is correct at this point.

- [ ] **Step 8: Commit**

```bash
git add pom.xml mvnw mvnw.cmd .mvn .gitattributes src/
git commit -m "chore: bootstrap Spring Boot project skeleton

Maven wrapper, Java 25, Spring Boot 4.1.1, JdbcClient, Flyway,
Testcontainers dependency wiring for the ledger module.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: Testcontainers integration harness

**Files:**
- Create: `src/test/java/com/leandrossb/nummus/testutils/IntegrationTestBase.java`
- Create: `src/test/java/com/leandrossb/nummus/ApplicationSmokeTest.java`

**Interfaces:**
- Consumes: `Application` (Task 1).
- Produces: `abstract class IntegrationTestBase` — every integration test extends it. It exposes:
  - `static PostgreSQLContainer<?> POSTGRES` (already started; singleton pattern — the container is shared by all test classes so the Spring context stays cached),
  - `protected static Connection adminConnection() throws SQLException` (connects as the container superuser = Flyway/owner role),
  - `protected static Connection appConnection() throws SQLException` (connects as `nummus_app` with password `nummus-app-test`; only usable after Task 9 enables the login).

- [ ] **Step 1: Write `IntegrationTestBase`**

```java
package com.leandrossb.nummus.testutils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Base class for integration tests: shared PostgreSQL container and Spring context. */
@SpringBootTest
public abstract class IntegrationTestBase {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void registerDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  /** Superuser connection — the Flyway/owner role. Use for raw-SQL probes. */
  protected static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Least-privileged application-role connection (usable once Task 9 enables its login). */
  protected static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "nummus_app", "nummus-app-test");
  }
}
```

The manual static start (instead of `@Testcontainers`/`@Container`) is deliberate: the JUnit extension would stop the container after each test class while the Spring context stays cached across classes, leaving the cached context pointing at a dead database. The singleton container lives for the whole JVM.

- [ ] **Step 2: Write the smoke test**

```java
package com.leandrossb.nummus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class ApplicationSmokeTest extends IntegrationTestBase {

  @Test
  void springContextStartsAndFlywayRuns() {
    assertDoesNotThrow(() -> {
      // Context startup already ran Flyway (zero migrations is a valid state).
      // Reaching here means datasource + Flyway wiring are correct.
    });
  }
}
```

- [ ] **Step 3: Run it (first Testcontainers run pulls the image)**

```bash
./mvnw test -Dtest=ApplicationSmokeTest
```

Expected: PASS (`docker info` must succeed; first run pulls `postgres:18-alpine`, several minutes).

- [ ] **Step 4: Commit**

```bash
git add src/test/
git commit -m "test: add Testcontainers integration harness

Shared PostgreSQL 18 container with a cached Spring context; admin and
app-role connection helpers for database-level invariant probes.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: `Money` value object (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/ledger/domain/Money.java`
- Create: `src/main/java/com/leandrossb/nummus/ledger/domain/InvalidMoneyException.java`
- Create: `src/main/java/com/leandrossb/nummus/ledger/domain/CurrencyMismatchException.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/domain/MoneyTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 4-13):
  - `record Money(BigDecimal amount, Currency currency)` with `static Money of(BigDecimal, Currency)`, `static Money ofBrl(String)`, `Money add(Money)`, `Money subtract(Money)`, `int compareTo(Money)`, `boolean isPositive()`, `boolean isNegative()`, `boolean isZero()`; constructor rejects nulls and scale > 4 with `InvalidMoneyException`.
  - `InvalidMoneyException(String message)` and `CurrencyMismatchException(String message)`, both `extends RuntimeException`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void acceptsAmountsWithScaleUpToFour() {
    Money.of(new BigDecimal("150"), BRL);
    Money.of(new BigDecimal("150.00"), BRL);
    Money.of(new BigDecimal("150.0000"), BRL);
    Money.ofBrl("0.0001");
  }

  @Test
  void rejectsScaleBeyondFour() {
    var ex = assertThrows(InvalidMoneyException.class, () -> Money.ofBrl("1.23456"));
    assertTrue(ex.getMessage().contains("scale"));
  }

  @Test
  void rejectsNullAmountOrCurrency() {
    assertThrows(NullPointerException.class, () -> Money.of(null, BRL));
    assertThrows(NullPointerException.class, () -> Money.of(BigDecimal.TEN, null));
  }

  @Test
  void negativeAmountsAreAllowedBecauseBalancesCanBeNegative() {
    assertTrue(Money.ofBrl("-3.5000").isNegative());
    assertTrue(Money.ofBrl("-3.5000").subtract(Money.ofBrl("1.0000")).isNegative());
  }

  @Test
  void compareToIgnoresScaleDifferences() {
    assertEquals(0, Money.ofBrl("2.1").compareTo(Money.ofBrl("2.10")));
    assertEquals(0, Money.ofBrl("2.10").compareTo(Money.ofBrl("2.1")));
    assertTrue(Money.ofBrl("2.10").compareTo(Money.ofBrl("2.11")) < 0);
  }

  @Test
  void arithmeticChecksCurrency() {
    assertThrows(CurrencyMismatchException.class, () -> Money.ofBrl("1.00").add(Money.of(BigDecimal.ONE, USD)));
    assertThrows(CurrencyMismatchException.class, () -> Money.ofBrl("1.00").subtract(Money.of(BigDecimal.ONE, USD)));
    assertEquals(Money.ofBrl("3.0000").compareTo(Money.ofBrl("1.1000").add(Money.ofBrl("1.9000"))), 0);
  }

  @Test
  void zeroHelpersWork() {
    assertTrue(Money.ofBrl("0.0000").isZero());
    assertTrue(Money.ofBrl("5.0000").isPositive());
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

```bash
./mvnw test -Dtest=MoneyTest
```

Expected: COMPILATION ERROR (`Money` / exceptions do not exist). This is the failing state.

- [ ] **Step 3: Implement**

`InvalidMoneyException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Thrown when a monetary amount violates the ledger's precision or sign rules. */
public class InvalidMoneyException extends RuntimeException {

  public InvalidMoneyException(String message) {
    super(message);
  }
}
```

`CurrencyMismatchException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Thrown when amounts in different currencies are combined, or a non-BRL amount reaches the BRL ledger. */
public class CurrencyMismatchException extends RuntimeException {

  public CurrencyMismatchException(String message) {
    super(message);
  }
}
```

`Money.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount with explicit currency. Amounts are never {@code double}/{@code float};
 * comparisons use {@link BigDecimal#compareTo} so {@code 2.1} and {@code 2.10} are equal.
 * Negative amounts are valid (derived balances swing both ways); posting-level positivity
 * is enforced by {@link PostingDraft}.
 */
public record Money(BigDecimal amount, Currency currency) {

  private static final int MAX_SCALE = 4;

  public Money {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
    if (amount.scale() > MAX_SCALE) {
      throw new InvalidMoneyException(
          "amount scale must be at most " + MAX_SCALE + ": " + amount.toPlainString());
    }
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  public static Money ofBrl(String amount) {
    return of(new BigDecimal(amount), Currency.getInstance("BRL"));
  }

  public Money add(Money other) {
    assertSameCurrency(other);
    return of(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    assertSameCurrency(other);
    return of(amount.subtract(other.amount), currency);
  }

  public int compareTo(Money other) {
    assertSameCurrency(other);
    return amount.compareTo(other.amount);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public boolean isNegative() {
    return amount.signum() < 0;
  }

  public boolean isZero() {
    return amount.signum() == 0;
  }

  private void assertSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new CurrencyMismatchException(
          "currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
    }
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./mvnw test -Dtest=MoneyTest
```

Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/domain/ src/test/java/com/leandrossb/nummus/ledger/domain/
git commit -m "feat: add Money value object with four-decimal precision

BigDecimal-only amounts, explicit currency, scale cap of 4, and
compareTo-based equality so 2.1 and 2.10 never diverge.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Ledger enums, records, commands, and domain exceptions

**Files:**
- Create in `src/main/java/com/leandrossb/nummus/ledger/domain/`: `Direction.java`, `AccountType.java`, `AccountStatus.java`, `Page.java`, `PostingDraft.java`, `PostedPosting.java`, `PostedTransaction.java`, `StatementLine.java`, `AccountStatement.java`, `LedgerAccount.java`, `TooFewPostingsException.java`, `UnbalancedTransactionException.java`, `UnknownAccountException.java`, `AccountNotActiveException.java`, `UnknownTransactionException.java`, `TransactionAlreadyReversedException.java`
- Create in `src/main/java/com/leandrossb/nummus/ledger/application/`: `OpenAccountCommand.java`, `PostTransactionCommand.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/domain/AccountTypeTest.java`, `PostingDraftTest.java`, `PageTest.java`

**Interfaces:**
- Consumes: `Money`, `InvalidMoneyException`, `CurrencyMismatchException` (Task 3).
- Produces (exact signatures used by Tasks 5-13):
  - `enum Direction { DEBIT, CREDIT; Direction opposite(); }`
  - `enum AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE; Direction normalBalance(); }`
  - `enum AccountStatus { ACTIVE, FROZEN, CLOSED }`
  - `record LedgerAccount(UUID publicId, String name, AccountType type, Currency currency, AccountStatus status, Instant openedAt, Instant closedAt)`
  - `record PostingDraft(UUID accountPublicId, Direction direction, Money amount)` — constructor rejects nulls and non-positive amounts (`InvalidMoneyException`)
  - `record PostedPosting(UUID accountPublicId, Direction direction, Money amount)`
  - `record PostedTransaction(UUID publicId, String memo, Instant bookedAt, UUID reversalOf, List<PostedPosting> postings)`
  - `record StatementLine(Instant bookedAt, UUID transactionPublicId, String memo, Direction direction, Money amount)`
  - `record AccountStatement(LedgerAccount account, Money balance, List<StatementLine> lines)`
  - `record Page(int offset, int limit)` — `offset >= 0`, `1 <= limit <= 500`, else `IllegalArgumentException`
  - `record OpenAccountCommand(String name, AccountType type, Currency currency)`
  - `record PostTransactionCommand(String memo, List<PostingDraft> postings)`
  - Exceptions, all `extends RuntimeException`: `TooFewPostingsException(int postingCount)`, `UnbalancedTransactionException(Money debits, Money credits)`, `UnknownAccountException(UUID publicId)`, `AccountNotActiveException(UUID publicId, AccountStatus status)`, `UnknownTransactionException(UUID publicId)`, `TransactionAlreadyReversedException(UUID publicId, Throwable cause)`

- [ ] **Step 1: Write the failing tests**

`AccountTypeTest.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

  @Test
  void assetAndExpenseHaveDebitNormalBalance() {
    assertEquals(Direction.DEBIT, AccountType.ASSET.normalBalance());
    assertEquals(Direction.DEBIT, AccountType.EXPENSE.normalBalance());
  }

  @Test
  void liabilityEquityRevenueHaveCreditNormalBalance() {
    assertEquals(Direction.CREDIT, AccountType.LIABILITY.normalBalance());
    assertEquals(Direction.CREDIT, AccountType.EQUITY.normalBalance());
    assertEquals(Direction.CREDIT, AccountType.REVENUE.normalBalance());
  }
}
```

`PostingDraftTest.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingDraftTest {

  @Test
  void requiresStrictlyPositiveAmount() {
    assertThrows(InvalidMoneyException.class,
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("0.0000")));
    assertThrows(InvalidMoneyException.class,
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("-1.0000")));
    assertDoesNotThrow(
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("0.0001")));
  }

  @Test
  void requiresAccountAndDirection() {
    var amount = Money.ofBrl("1.0000");
    assertThrows(NullPointerException.class, () -> new PostingDraft(null, Direction.DEBIT, amount));
    assertThrows(NullPointerException.class,
        () -> new PostingDraft(UUID.randomUUID(), null, amount));
  }
}
```

`PageTest.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageTest {

  @Test
  void validatesOffsetAndLimit() {
    assertDoesNotThrow(() -> new Page(0, 1));
    assertDoesNotThrow(() -> new Page(10_000, 500));
    assertThrows(IllegalArgumentException.class, () -> new Page(-1, 10));
    assertThrows(IllegalArgumentException.class, () -> new Page(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Page(0, 501));
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./mvnw test -Dtest='AccountTypeTest,PostingDraftTest,PageTest'
```

Expected: COMPILATION ERROR (types do not exist).

- [ ] **Step 3: Implement**

`Direction.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Posting side in the double-entry journal. */
public enum Direction {
  DEBIT,
  CREDIT;

  public Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }
}
```

`AccountType.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/**
 * Classic chart-of-accounts classification. Each type carries the side on which
 * the account's balance grows naturally; the database stores only this enum.
 */
public enum AccountType {
  ASSET,
  LIABILITY,
  EQUITY,
  REVENUE,
  EXPENSE;

  public Direction normalBalance() {
    return (this == ASSET || this == EXPENSE) ? Direction.DEBIT : Direction.CREDIT;
  }
}
```

`AccountStatus.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Account lifecycle: CLOSED is terminal; FROZEN and ACTIVE are reversible. */
public enum AccountStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
```

`LedgerAccount.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/** A ledger account in the module-owned chart of accounts. {@code closedAt} is null while open. */
public record LedgerAccount(
    UUID publicId,
    String name,
    AccountType type,
    Currency currency,
    AccountStatus status,
    Instant openedAt,
    Instant closedAt) {
}
```

`PostingDraft.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/** A posting to be written as part of a new journal transaction. Amount must be strictly positive. */
public record PostingDraft(UUID accountPublicId, Direction direction, Money amount) {

  public PostingDraft {
    Objects.requireNonNull(accountPublicId, "accountPublicId must not be null");
    Objects.requireNonNull(direction, "direction must not be null");
    Objects.requireNonNull(amount, "amount must not be null");
    if (!amount.isPositive()) {
      throw new InvalidMoneyException(
          "posting amount must be strictly positive: " + amount.amount().toPlainString());
    }
  }
}
```

`PostedPosting.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** A posting as persisted in the journal. */
public record PostedPosting(UUID accountPublicId, Direction direction, Money amount) {
}
```

`PostedTransaction.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A committed journal transaction. {@code reversalOf} points at the original when this is a reversal. */
public record PostedTransaction(
    UUID publicId,
    String memo,
    Instant bookedAt,
    UUID reversalOf,
    List<PostedPosting> postings) {
}
```

`StatementLine.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.UUID;

/** One posting in an account statement, with its originating transaction context. */
public record StatementLine(
    Instant bookedAt,
    UUID transactionPublicId,
    String memo,
    Direction direction,
    Money amount) {
}
```

`AccountStatement.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.List;

/** Paginated account view: the account, its derived balance, and postings newest first. */
public record AccountStatement(LedgerAccount account, Money balance, List<StatementLine> lines) {
}
```

`Page.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Offset-based pagination for statements. */
public record Page(int offset, int limit) {

  public Page {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0: " + offset);
    }
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("limit must be between 1 and 500: " + limit);
    }
  }
}
```

`TooFewPostingsException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Thrown when a transaction has fewer than two postings or misses one side of the entry. */
public class TooFewPostingsException extends RuntimeException {

  public TooFewPostingsException(int postingCount) {
    super("a transaction needs at least one DEBIT and one CREDIT posting, got " + postingCount);
  }
}
```

`UnbalancedTransactionException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

/** Thrown when total debits and total credits of a transaction differ. */
public class UnbalancedTransactionException extends RuntimeException {

  public UnbalancedTransactionException(Money debits, Money credits) {
    super("transaction is not balanced: debits=" + debits.amount().toPlainString()
        + " credits=" + credits.amount().toPlainString());
  }
}
```

`UnknownAccountException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when an account id does not exist. */
public class UnknownAccountException extends RuntimeException {

  public UnknownAccountException(UUID publicId) {
    super("unknown account: " + publicId);
  }
}
```

`AccountNotActiveException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when an operation requires an ACTIVE account but the account is FROZEN or CLOSED. */
public class AccountNotActiveException extends RuntimeException {

  public AccountNotActiveException(UUID publicId, AccountStatus status) {
    super("account " + publicId + " is not ACTIVE: " + status);
  }
}
```

`UnknownTransactionException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when a transaction id does not exist. */
public class UnknownTransactionException extends RuntimeException {

  public UnknownTransactionException(UUID publicId) {
    super("unknown transaction: " + publicId);
  }
}
```

`TransactionAlreadyReversedException.java`:

```java
package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** Thrown when reversing a transaction that has already been reversed. */
public class TransactionAlreadyReversedException extends RuntimeException {

  public TransactionAlreadyReversedException(UUID publicId, Throwable cause) {
    super("transaction " + publicId + " has already been reversed", cause);
  }
}
```

`OpenAccountCommand.java`:

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountType;
import java.util.Currency;

/** Command to open a new ledger account. */
public record OpenAccountCommand(String name, AccountType type, Currency currency) {
}
```

`PostTransactionCommand.java`:

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.PostingDraft;
import java.util.List;

/** Command to append a journal transaction. */
public record PostTransactionCommand(String memo, List<PostingDraft> postings) {
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./mvnw test -Dtest='AccountTypeTest,PostingDraftTest,PageTest'
```

Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/ src/test/java/com/leandrossb/nummus/ledger/domain/
git commit -m "feat: add ledger domain records, enums, commands, and exceptions

Chart-of-accounts classification with derived normal balance, posting
drafts with strict positivity, statement pagination, and the domain
exception vocabulary for the ledger port.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: `Ledger` and `LedgerRepository` ports + in-memory fake

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/ledger/application/Ledger.java`
- Create: `src/main/java/com/leandrossb/nummus/ledger/application/LedgerRepository.java`
- Create: `src/test/java/com/leandrossb/nummus/ledger/application/InMemoryLedgerRepository.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/application/InMemoryLedgerRepositoryTest.java`

**Interfaces:**
- Consumes: everything from Tasks 3-4.
- Produces (the module's contract; Tasks 6-13 implement against these):
  - `interface Ledger` (exact signatures): `LedgerAccount openAccount(OpenAccountCommand)`, `LedgerAccount freezeAccount(UUID)`, `LedgerAccount closeAccount(UUID)`, `PostedTransaction post(PostTransactionCommand)`, `PostedTransaction reverse(UUID transactionPublicId, String memo)`, `Money balance(UUID accountPublicId)`, `AccountStatement statement(UUID accountPublicId, Page page)`, `PostedTransaction getTransaction(UUID txPublicId)`
  - `interface LedgerRepository`: `LedgerAccount insertAccount(LedgerAccount)`, `Optional<LedgerAccount> findAccount(UUID)`, `boolean updateAccountStatus(UUID, AccountStatus, Instant closedAt)`, `Optional<PostedTransaction> findTransaction(UUID)`, `PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId, List<PostingDraft>)`, `BigDecimal rawBalance(UUID accountPublicId)`, `List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit)`
  - Test-only: `InMemoryLedgerRepository implements LedgerRepository` — `insertTransaction` throws `org.springframework.dao.DataIntegrityViolationException` when the `reversalOfPublicId` was already used, mirroring the database's unique constraint (Task 6's service maps it to `TransactionAlreadyReversedException`).

- [ ] **Step 1: Write the failing fake test**

```java
package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryLedgerRepositoryTest {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Test
  void accountsRoundTrip() {
    var repo = new InMemoryLedgerRepository();
    var account = new LedgerAccount(UUID.randomUUID(), "cash", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(account);
    assertEquals(account, repo.findAccount(account.publicId()).orElseThrow());
    assertTrue(repo.updateAccountStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repo.findAccount(account.publicId()).orElseThrow().status());
  }

  @Test
  void transactionsRoundTripAndBalanceSums() {
    var repo = new InMemoryLedgerRepository();
    var debit = new LedgerAccount(UUID.randomUUID(), "debit", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    var credit = new LedgerAccount(UUID.randomUUID(), "credit", AccountType.LIABILITY, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(debit);
    repo.insertAccount(credit);

    var posted = repo.insertTransaction("t1", null, List.of(
        new PostingDraft(debit.publicId(), Direction.DEBIT, Money.ofBrl("10.0000")),
        new PostingDraft(credit.publicId(), Direction.CREDIT, Money.ofBrl("10.0000"))));
    assertEquals(posted, repo.findTransaction(posted.publicId()).orElseThrow());
    assertEquals(0, repo.rawBalance(debit.publicId()).compareTo(new BigDecimal("10.0000")));
    assertEquals(0, repo.rawBalance(credit.publicId()).compareTo(new BigDecimal("-10.0000")));
  }

  @Test
  void duplicateReversalThrowsDataIntegrityViolation() {
    var repo = new InMemoryLedgerRepository();
    var debit = new LedgerAccount(UUID.randomUUID(), "d", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    var credit = new LedgerAccount(UUID.randomUUID(), "c", AccountType.LIABILITY, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(debit);
    repo.insertAccount(credit);
    var drafts = List.of(
        new PostingDraft(debit.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(credit.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")));
    var original = repo.insertTransaction("t1", null, drafts);
    repo.insertTransaction("reversal", original.publicId(), drafts);

    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> repo.insertTransaction("again", original.publicId(), drafts));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw test -Dtest=InMemoryLedgerRepositoryTest
```

Expected: COMPILATION ERROR (ports and fake do not exist).

- [ ] **Step 3: Implement**

`Ledger.java`:

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import java.util.UUID;

/**
 * The ledger module's internal API. M2+ modules call this port; HTTP arrives in M2.
 * Deliberately NOT idempotent — merchant-facing retry semantics belong to the
 * idempotency layer (M4), not to the accounting core.
 */
public interface Ledger {

  LedgerAccount openAccount(OpenAccountCommand cmd);

  LedgerAccount freezeAccount(UUID publicId);

  LedgerAccount closeAccount(UUID publicId);

  /** Appends a balanced journal transaction atomically. */
  PostedTransaction post(PostTransactionCommand cmd);

  /** Appends a compensating transaction mirroring the original's postings. */
  PostedTransaction reverse(UUID transactionPublicId, String memo);

  /** Raw derived balance (debits minus credits), signed regardless of the account's normal side. */
  Money balance(UUID accountPublicId);

  /** The account's postings, newest first, with its derived balance. */
  AccountStatement statement(UUID accountPublicId, Page page);

  PostedTransaction getTransaction(UUID txPublicId);
}
```

`LedgerRepository.java`:

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the ledger module. Implementations append transaction and
 * postings atomically; database triggers re-assert every invariant at commit.
 * {@code insertTransaction} signals a duplicate reversal with
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 */
public interface LedgerRepository {

  LedgerAccount insertAccount(LedgerAccount account);

  Optional<LedgerAccount> findAccount(UUID publicId);

  /** @return false when the account does not exist. */
  boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt);

  Optional<PostedTransaction> findTransaction(UUID publicId);

  /** Appends the transaction and its postings in one database transaction. */
  PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId, List<PostingDraft> postings);

  /** Sum of DEBIT minus CREDIT postings for the account; 0 for an account without postings. */
  BigDecimal rawBalance(UUID accountPublicId);

  /** The account's postings newest first. */
  List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit);
}
```

`InMemoryLedgerRepository.java` (test scope):

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import com.leandrossb.nummus.ledger.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;

/** In-memory fake for service unit tests; mirrors the database's unique reversal_of behavior. */
class InMemoryLedgerRepository implements LedgerRepository {

  private final Map<UUID, LedgerAccount> accounts = new ConcurrentHashMap<>();
  private final Map<UUID, PostedTransaction> transactions = new ConcurrentHashMap<>();

  @Override
  public LedgerAccount insertAccount(LedgerAccount account) {
    accounts.put(account.publicId(), account);
    return account;
  }

  @Override
  public Optional<LedgerAccount> findAccount(UUID publicId) {
    return Optional.ofNullable(accounts.get(publicId));
  }

  @Override
  public boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    var current = accounts.get(publicId);
    if (current == null) {
      return false;
    }
    accounts.put(publicId, new LedgerAccount(current.publicId(), current.name(), current.type(),
        current.currency(), status, current.openedAt(), closedAt));
    return true;
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    return Optional.ofNullable(transactions.get(publicId));
  }

  @Override
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    if (reversalOfPublicId != null
        && transactions.values().stream().anyMatch(t -> reversalOfPublicId.equals(t.reversalOf()))) {
      throw new DataIntegrityViolationException(
          "duplicate reversal_of: " + reversalOfPublicId);
    }
    var posted = new PostedTransaction(UUID.randomUUID(), memo, Instant.now(), reversalOfPublicId,
        postings.stream()
            .map(d -> new PostedPosting(d.accountPublicId(), d.direction(), d.amount()))
            .toList());
    transactions.put(posted.publicId(), posted);
    return posted;
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    return transactions.values().stream()
        .flatMap(t -> t.postings().stream())
        .filter(p -> p.accountPublicId().equals(accountPublicId))
        .map(p -> p.direction() == Direction.DEBIT
            ? p.amount().amount()
            : p.amount().amount().negate())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    return transactions.values().stream()
        .sorted(Comparator.comparing(PostedTransaction::bookedAt).reversed())
        .flatMap(t -> t.postings().stream()
            .filter(p -> p.accountPublicId().equals(accountPublicId))
            .map(p -> new StatementLine(t.bookedAt(), t.publicId(), t.memo(), p.direction(),
                p.amount())))
        .skip(offset)
        .limit(limit)
        .toList();
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./mvnw test -Dtest=InMemoryLedgerRepositoryTest
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/application/ src/test/java/com/leandrossb/nummus/ledger/application/
git commit -m "feat: add Ledger and LedgerRepository ports with in-memory fake

The module's internal contract: no idempotency by design, reversal
uniqueness surfaced as a data integrity violation.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: `LedgerServiceImpl` (TDD against the fake)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/ledger/application/LedgerServiceImpl.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/application/LedgerServiceAccountsTest.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/application/LedgerServicePostTest.java`

**Interfaces:**
- Consumes: `Ledger`, `LedgerRepository`, commands, domain types and exceptions (Tasks 3-5).
- Produces: `@Service class LedgerServiceImpl implements Ledger`, constructor `LedgerServiceImpl(LedgerRepository repository)`. Spring wires it in integration contexts (Tasks 10-13) because the fake lives in test scope only.

- [ ] **Step 1: Write the failing tests**

`LedgerServiceAccountsTest.java`:

```java
package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServiceAccountsTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());

  @Test
  void openAccountDefaultsToActiveWithPublicId() {
    var account = ledger.openAccount(new OpenAccountCommand("merchant payable",
        AccountType.LIABILITY, BRL));
    assertNotNull(account.publicId());
    assertEquals(AccountStatus.ACTIVE, account.status());
    assertEquals(BRL, account.currency());
    assertNull(account.closedAt());
  }

  @Test
  void openAccountRejectsNonBrlCurrency() {
    assertThrows(CurrencyMismatchException.class, () ->
        ledger.openAccount(new OpenAccountCommand("usd account", AccountType.ASSET,
            Currency.getInstance("USD"))));
  }

  @Test
  void openAccountRejectsBlankNameAndMissingType() {
    assertThrows(IllegalArgumentException.class, () ->
        ledger.openAccount(new OpenAccountCommand("  ", AccountType.ASSET, BRL)));
    assertThrows(NullPointerException.class, () ->
        ledger.openAccount(new OpenAccountCommand("name", null, BRL)));
  }

  @Test
  void freezeAndCloseTransitionStatus() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(AccountStatus.FROZEN, ledger.freezeAccount(account.publicId()).status());
    assertEquals(AccountStatus.ACTIVE, ledger.openAccount(
        new OpenAccountCommand("b", AccountType.ASSET, BRL)).status()); // sanity
    var closed = ledger.closeAccount(account.publicId());
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertNotNull(closed.closedAt());
  }

  @Test
  void closedIsTerminalAndUnknownAccountsThrow() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    ledger.closeAccount(account.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.freezeAccount(account.publicId()));
    assertThrows(AccountNotActiveException.class, () -> ledger.closeAccount(account.publicId()));
    assertThrows(UnknownAccountException.class, () -> ledger.freezeAccount(UUID.randomUUID()));
    assertThrows(UnknownAccountException.class, () -> ledger.balance(UUID.randomUUID()));
    assertThrows(UnknownAccountException.class,
        () -> ledger.statement(UUID.randomUUID(), new com.leandrossb.nummus.ledger.domain.Page(0, 50)));
  }

  @Test
  void balanceOfAccountWithoutPostingsIsZero() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(0, ledger.balance(account.publicId()).compareTo(
        com.leandrossb.nummus.ledger.domain.Money.ofBrl("0.0000")));
  }
}
```

`LedgerServicePostTest.java`:

```java
package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.TooFewPostingsException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnbalancedTransactionException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServicePostTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());

  private com.leandrossb.nummus.ledger.domain.LedgerAccount account(String name, AccountType type) {
    return ledger.openAccount(new OpenAccountCommand(name, type, BRL));
  }

  @Test
  void postReturnsCommittedTransactionAndUpdatesBalance() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    assertNotNull(posted.publicId());
    assertNotNull(posted.bookedAt());
    assertEquals(2, posted.postings().size());
    assertEquals("funding", posted.memo());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));
    assertEquals(0, ledger.balance(liability.publicId()).compareTo(Money.ofBrl("-150.0000")));
  }

  @Test
  void postRejectsTooFewOrOneSidedPostings() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var amount = Money.ofBrl("1.0000");
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("null postings", null)));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("empty", List.of())));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("single", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, amount)))));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("two debits", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, amount),
            new PostingDraft(liability.publicId(), Direction.DEBIT, amount)))));
  }

  @Test
  void postRejectsUnbalancedAmounts() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    assertThrows(UnbalancedTransactionException.class, () ->
        ledger.post(new PostTransactionCommand("unbalanced", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("10.0000")),
            new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("9.9999"))))));
  }

  @Test
  void postAcceptsBalancedAmountsWithDifferentScales() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("scale tolerant", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("10.0")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("10.0000")))));
    assertNotNull(posted.publicId());
  }

  @Test
  void postRejectsUnknownFrozenOrForeignCurrencyAccounts() {
    var asset = account("cash", AccountType.ASSET);
    assertThrows(UnknownAccountException.class, () ->
        ledger.post(new PostTransactionCommand("unknown", List.of(
            new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("1.0000")),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));

    var frozen = account("frozen cash", AccountType.ASSET);
    ledger.freezeAccount(frozen.publicId());
    assertThrows(AccountNotActiveException.class, () ->
        ledger.post(new PostTransactionCommand("frozen", List.of(
            new PostingDraft(frozen.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));

    assertThrows(CurrencyMismatchException.class, () ->
        ledger.post(new PostTransactionCommand("wrong currency", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT,
                Money.of(BigDecimal.ONE, Currency.getInstance("USD"))),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));
  }

  @Test
  void reverseMirrorsPostingsAndDoubleReverseThrows() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var original = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("50.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("50.0000")))));

    var reversal = ledger.reverse(original.publicId(), "undo funding");
    assertEquals(original.publicId(), reversal.reversalOf());
    assertEquals(2, reversal.postings().size());
    assertEquals(Direction.CREDIT, reversal.postings().get(0).direction());
    assertEquals(Direction.DEBIT, reversal.postings().get(1).direction());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0.0000")));

    assertThrows(TransactionAlreadyReversedException.class,
        () -> ledger.reverse(original.publicId(), "again"));
    assertThrows(UnknownTransactionException.class,
        () -> ledger.reverse(UUID.randomUUID(), "nope"));
  }

  @Test
  void reversingAReversalIsAllowedAndRestoresBalance() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var original = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("50.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("50.0000")))));
    var reversal = ledger.reverse(original.publicId(), "undo");
    ledger.reverse(reversal.publicId(), "redo");
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("50.0000")));
  }

  @Test
  void getTransactionRoundTrips() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("t", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
    PostedTransaction fetched = ledger.getTransaction(posted.publicId());
    assertEquals(posted.publicId(), fetched.publicId());
    assertThrows(UnknownTransactionException.class, () -> ledger.getTransaction(UUID.randomUUID()));
  }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
./mvnw test -Dtest='LedgerServiceAccountsTest,LedgerServicePostTest'
```

Expected: COMPILATION ERROR (`LedgerServiceImpl` does not exist).

- [ ] **Step 3: Implement `LedgerServiceImpl.java`**

```java
package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.TooFewPostingsException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnbalancedTransactionException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerServiceImpl implements Ledger {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final LedgerRepository repository;

  public LedgerServiceImpl(LedgerRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public LedgerAccount openAccount(OpenAccountCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw new IllegalArgumentException("account name must not be blank");
    }
    Objects.requireNonNull(cmd.type(), "account type must not be null");
    if (!BRL.equals(cmd.currency())) {
      throw new CurrencyMismatchException(
          "the ledger is BRL-only, got: " + (cmd.currency() == null ? "null" : cmd.currency().getCurrencyCode()));
    }
    return repository.insertAccount(new LedgerAccount(UUID.randomUUID(), cmd.name().trim(),
        cmd.type(), BRL, AccountStatus.ACTIVE, Instant.now(), null));
  }

  @Override
  @Transactional
  public LedgerAccount freezeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.FROZEN, null);
  }

  @Override
  @Transactional
  public LedgerAccount closeAccount(UUID publicId) {
    return transitionStatus(publicId, AccountStatus.CLOSED, Instant.now());
  }

  private LedgerAccount transitionStatus(UUID publicId, AccountStatus target, Instant closedAt) {
    var current = repository.findAccount(publicId)
        .orElseThrow(() -> new UnknownAccountException(publicId));
    if (current.status() == AccountStatus.CLOSED) {
      throw new AccountNotActiveException(publicId, current.status());
    }
    repository.updateAccountStatus(publicId, target, closedAt);
    return repository.findAccount(publicId).orElseThrow();
  }

  @Override
  @Transactional
  public PostedTransaction post(PostTransactionCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    requireValidPostings(cmd.postings());
    return repository.insertTransaction(cmd.memo(), null, cmd.postings());
  }

  @Override
  @Transactional
  public PostedTransaction reverse(UUID transactionPublicId, String memo) {
    var original = repository.findTransaction(transactionPublicId)
        .orElseThrow(() -> new UnknownTransactionException(transactionPublicId));
    List<PostingDraft> mirrored = original.postings().stream()
        .map(p -> new PostingDraft(p.accountPublicId(), p.direction().opposite(), p.amount()))
        .toList();
    try {
      return repository.insertTransaction(memo, transactionPublicId, mirrored);
    } catch (DataIntegrityViolationException e) {
      throw new TransactionAlreadyReversedException(transactionPublicId, e);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Money balance(UUID accountPublicId) {
    var account = requireAccount(accountPublicId);
    return Money.of(repository.rawBalance(accountPublicId), account.currency());
  }

  @Override
  @Transactional(readOnly = true)
  public AccountStatement statement(UUID accountPublicId, Page page) {
    Objects.requireNonNull(page, "page must not be null");
    var account = requireAccount(accountPublicId);
    return new AccountStatement(account,
        Money.of(repository.rawBalance(accountPublicId), account.currency()),
        repository.statementLines(accountPublicId, page.offset(), page.limit()));
  }

  @Override
  @Transactional(readOnly = true)
  public PostedTransaction getTransaction(UUID txPublicId) {
    return repository.findTransaction(txPublicId)
        .orElseThrow(() -> new UnknownTransactionException(txPublicId));
  }

  private LedgerAccount requireAccount(UUID publicId) {
    return repository.findAccount(publicId).orElseThrow(() -> new UnknownAccountException(publicId));
  }

  private void requireValidPostings(List<PostingDraft> postings) {
    if (postings == null || postings.size() < 2) {
      throw new TooFewPostingsException(postings == null ? 0 : postings.size());
    }
    boolean hasDebit = postings.stream().anyMatch(p -> p.direction() == Direction.DEBIT);
    boolean hasCredit = postings.stream().anyMatch(p -> p.direction() == Direction.CREDIT);
    if (!hasDebit || !hasCredit) {
      throw new TooFewPostingsException(postings.size());
    }
    for (PostingDraft draft : postings) {
      var account = repository.findAccount(draft.accountPublicId())
          .orElseThrow(() -> new UnknownAccountException(draft.accountPublicId()));
      if (account.status() != AccountStatus.ACTIVE) {
        throw new AccountNotActiveException(draft.accountPublicId(), account.status());
      }
      if (!BRL.equals(draft.amount().currency())) {
        throw new CurrencyMismatchException(
            "the ledger is BRL-only, got: " + draft.amount().currency().getCurrencyCode());
      }
    }
    Money debits = total(postings, Direction.DEBIT);
    Money credits = total(postings, Direction.CREDIT);
    if (debits.compareTo(credits) != 0) {
      throw new UnbalancedTransactionException(debits, credits);
    }
  }

  private Money total(List<PostingDraft> postings, Direction direction) {
    return postings.stream()
        .filter(p -> p.direction() == direction)
        .map(PostingDraft::amount)
        .reduce(Money.ofBrl("0"), Money::add);
  }
}
```

- [ ] **Step 4: Run to verify they pass**

```bash
./mvnw test -Dtest='LedgerServiceAccountsTest,LedgerServicePostTest'
```

Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/application/LedgerServiceImpl.java src/test/java/com/leandrossb/nummus/ledger/application/
git commit -m "feat: add ledger service with fail-fast validation and reversals

Single-decision-point posting validation (count, sides, account status,
currency, balance) plus compensating-entry reversal mapped from the
database's unique reversal_of violation.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Migration V1 — schema (TDD against the container)

**Files:**
- Create: `src/main/resources/db/migration/V1__ledger_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerSchemaTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase` (Task 2).
- Produces: PostgreSQL schema `ledger` with `ledger_account`, `journal_transaction`, `journal_posting`, and the balance-derivation indexes. Later migrations build on it; the repository (Tasks 10-11) reads/writes these tables.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerSchemaTest extends IntegrationTestBase {

  @Test
  void schemaTablesAcceptRowsAndGeneratePublicIds() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    String txPublicId = UUID.randomUUID().toString();
    try (Connection c = adminConnection()) {
      try (Statement st = c.createStatement()) {
        st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
            + accountPublicId + "', 'schema test', 'ASSET')");
        st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
            + txPublicId + "', 'schema test')");
        st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
            + "SELECT t.id, a.id, 'DEBIT', 10.0000 FROM ledger.journal_transaction t, ledger.ledger_account a "
            + "WHERE t.public_id = '" + txPublicId + "' AND a.public_id = '" + accountPublicId + "'");
      }
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(
              "SELECT currency, status FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'")) {
        assertTrue(rs.next());
        assertEquals("BRL", rs.getString(1).trim());
        assertEquals("ACTIVE", rs.getString(2));
      }
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw test -Dtest=LedgerSchemaTest
```

Expected: FAIL with `relation "ledger.ledger_account" does not exist` (Flyway has no migrations yet — context starts fine, the SQL probe fails).

- [ ] **Step 3: Write `V1__ledger_schema.sql`**

```sql
-- M1 ledger core: module-owned schema. Currency lives only on the account,
-- making the whole journal BRL by construction until multi-currency arrives.

create schema ledger;

create table ledger.ledger_account (
  id         bigint generated always as identity primary key,
  public_id  uuid not null default gen_random_uuid() unique,
  name       text not null,
  type       text not null check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
  currency   char(3) not null default 'BRL' check (currency = 'BRL'),
  status     text not null default 'ACTIVE' check (status in ('ACTIVE', 'FROZEN', 'CLOSED')),
  opened_at  timestamptz not null default now(),
  closed_at  timestamptz
);

create table ledger.journal_transaction (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  memo        text,
  booked_at   timestamptz not null default now(),
  reversal_of bigint references ledger.journal_transaction(id),
  unique (reversal_of)
);

create table ledger.journal_posting (
  id             bigint generated always as identity primary key,
  transaction_id bigint not null references ledger.journal_transaction(id),
  account_id     bigint not null references ledger.ledger_account(id),
  direction      text not null check (direction in ('DEBIT', 'CREDIT')),
  amount         numeric(19,4) not null check (amount > 0)
);

create index journal_posting_account_idx
  on ledger.journal_posting (account_id) include (direction, amount);

create index journal_posting_transaction_idx
  on ledger.journal_posting (transaction_id);
```

- [ ] **Step 4: Run to verify it passes**

```bash
./mvnw test -Dtest=LedgerSchemaTest
```

Expected: PASS. (Flyway applies V1 during context startup.)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V1__ledger_schema.sql src/test/java/com/leandrossb/nummus/ledger/LedgerSchemaTest.java
git commit -m "feat: add ledger schema migration

Module-owned PostgreSQL schema with ledger accounts, journal
transactions, postings, unique public ids, and the covering index for
balance derivation.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Migration V2 — enforcement triggers (TDD)

**Files:**
- Create: `src/main/resources/db/migration/V2__ledger_enforcement.sql`
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerEnforcementTest.java`

**Interfaces:**
- Consumes: V1 schema (Task 7), `IntegrationTestBase` (Task 2).
- Produces: database-enforced invariants — balanced transactions (deferred constraint trigger), append-only journal (BEFORE UPDATE/DELETE triggers), no account deletion. No Java code depends on trigger internals; they re-assert what `LedgerServiceImpl` validates.

- [ ] **Step 1: Write the failing tests**

All fixture SQL runs inside explicit transactions (autocommit would commit half-written entries and trip the deferred trigger early). Helper `insertTx` inserts one transaction with N postings and commits.

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

class LedgerEnforcementTest extends IntegrationTestBase {

  private String insertAccount(Connection c, String name) throws SQLException {
    String publicId = UUID.randomUUID().toString();
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO ledger.ledger_account (public_id, name, type) VALUES (?, ?, 'ASSET')")) {
      ps.setObject(1, UUID.fromString(publicId));
      ps.setString(2, name);
      ps.executeUpdate();
    }
    return publicId;
  }

  /** Posts (direction, amount, accountName) triples under one new transaction; commits. */
  private void insertTx(String[][] postings) throws SQLException {
    try (Connection c = adminConnection()) {
      c.setAutoCommit(false);
      String txPublicId = UUID.randomUUID().toString();
      try (Statement st = c.createStatement()) {
        st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
            + txPublicId + "', 'enforcement test')");
      }
      for (String[] posting : postings) {
        try (Statement st = c.createStatement()) {
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, '" + posting[0] + "', " + posting[1]
              + " FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txPublicId + "' AND a.name = '" + posting[2] + "'");
        }
      }
      c.commit();
    }
  }

  @Test
  void balancedTransactionCommits() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "bal-asset");
      insertAccount(c, "bal-liability");
    }
    assertDoesNotThrow(() -> insertTx(new String[][] {
        {"DEBIT", "10.0000", "bal-asset"},
        {"CREDIT", "10.0000", "bal-liability"}}));
  }

  @Test
  void unbalancedTransactionRejectedAtCommitEvenViaRawSql() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "unbal-asset");
      insertAccount(c, "unbal-liability");
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "10.0000", "unbal-asset"},
          {"CREDIT", "9.0000", "unbal-liability"}});
      fail("unbalanced transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
  }

  @Test
  void singlePostingAndOneSidedTransactionsRejected() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "one-asset");
    }
    try {
      insertTx(new String[][] {{"DEBIT", "5.0000", "one-asset"}});
      fail("single-sided transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
    try (Connection c = adminConnection()) {
      insertAccount(c, "one-liability");
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "5.0000", "one-asset"},
          {"DEBIT", "5.0000", "one-liability"}});
      fail("two-debit transaction must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("unbalanced"), rootMessage(e));
    }
  }

  @Test
  void postingsToFrozenOrClosedAccountsRejected() throws Exception {
    try (Connection c = adminConnection()) {
      insertAccount(c, "frozen-asset");
      insertAccount(c, "frozen-liability");
      try (Statement st = c.createStatement()) {
        st.executeUpdate("UPDATE ledger.ledger_account SET status = 'FROZEN' WHERE name = 'frozen-liability'");
      }
    }
    try {
      insertTx(new String[][] {
          {"DEBIT", "5.0000", "frozen-asset"},
          {"CREDIT", "5.0000", "frozen-liability"}});
      fail("posting to FROZEN account must not commit");
    } catch (SQLException e) {
      assertTrue(rootMessage(e).contains("non-ACTIVE"), rootMessage(e));
    }
  }

  @Test
  void journalIsImmutableEvenForTheOwner() throws Exception {
    try (Connection c = adminConnection();
        Statement st = c.createStatement()) {
      try {
        st.executeUpdate("UPDATE ledger.journal_posting SET amount = 1.0000");
        fail("UPDATE on journal_posting must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
      try {
        st.executeUpdate("DELETE FROM ledger.journal_transaction");
        fail("DELETE on journal_transaction must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
      try {
        st.executeUpdate("DELETE FROM ledger.ledger_account");
        fail("DELETE on ledger_account must be blocked");
      } catch (PSQLException e) {
        assertTrue(rootMessage(e).contains("append-only"), rootMessage(e));
      }
    }
  }

  private static String rootMessage(Throwable t) {
    String message = t.getMessage();
    Throwable cause = t.getCause();
    while (cause != null) {
      message = cause.getMessage();
      cause = cause.getCause();
    }
    return message == null ? "" : message;
  }
}
```

Note: the `insertTx` helper runs inside an explicit transaction (`setAutoCommit(false)` + `commit()`) because the balanced-transaction trigger is deferred to COMMIT — statement-by-statement autocommit would reject the first posting of every multi-posting entry before the rest arrive.

- [ ] **Step 2: Run to verify they fail**

```bash
./mvnw test -Dtest=LedgerEnforcementTest
```

Expected: the rejection tests FAIL (the unbalanced/one-sided/frozen transactions commit — no triggers yet). `journalIsImmutableEvenForTheOwner` also FAILs (UPDATE/DELETE succeed).

- [ ] **Step 3: Write `V2__ledger_enforcement.sql`**

```sql
-- Layer 2 of invariant enforcement: the database defends itself against
-- every writer, including the schema owner and raw SQL.

create or replace function ledger.forbid_mutation() returns trigger
language plpgsql as $$
begin
  raise exception 'table % is append-only: % is not permitted', tg_table_name, tg_op;
end;
$$;

create trigger journal_transaction_immutable
  before update or delete on ledger.journal_transaction
  for each row execute function ledger.forbid_mutation();

create trigger journal_posting_immutable
  before update or delete on ledger.journal_posting
  for each row execute function ledger.forbid_mutation();

create trigger ledger_account_immutable
  before delete on ledger.ledger_account
  for each row execute function ledger.forbid_mutation();

-- Balanced-transaction invariant, checked at commit so multi-statement
-- inserts are never rejected mid-flight.

create or replace function ledger.assert_transaction_balanced() returns trigger
language plpgsql as $$
declare
  v_count   integer;
  v_debits  integer;
  v_credits integer;
  v_delta   numeric;
  v_inactive integer;
begin
  select count(*),
         count(*) filter (where direction = 'DEBIT'),
         count(*) filter (where direction = 'CREDIT'),
         coalesce(sum(amount) filter (where direction = 'DEBIT'), 0)
           - coalesce(sum(amount) filter (where direction = 'CREDIT'), 0)
    into v_count, v_debits, v_credits, v_delta
  from ledger.journal_posting
  where transaction_id = new.transaction_id;

  if v_count < 2 or v_debits < 1 or v_credits < 1 then
    raise exception 'transaction % is unbalanced: at least one DEBIT and one CREDIT posting are required (postings: %)',
      new.transaction_id, v_count;
  end if;

  if v_delta <> 0 then
    raise exception 'transaction % is unbalanced: debits minus credits = %',
      new.transaction_id, v_delta;
  end if;

  select count(*) into v_inactive
  from ledger.journal_posting p
  join ledger.ledger_account a on a.id = p.account_id
  where p.transaction_id = new.transaction_id
    and a.status <> 'ACTIVE';

  if v_inactive > 0 then
    raise exception 'transaction % posts to a non-ACTIVE account', new.transaction_id;
  end if;

  return null;
end;
$$;

create constraint trigger journal_posting_balanced
  after insert on ledger.journal_posting
  deferrable initially deferred
  for each row execute function ledger.assert_transaction_balanced();
```

- [ ] **Step 4: Run to verify they pass**

```bash
./mvnw test -Dtest=LedgerEnforcementTest
```

Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V2__ledger_enforcement.sql src/test/java/com/leandrossb/nummus/ledger/LedgerEnforcementTest.java
git commit -m "feat: enforce ledger invariants in the database

Deferred constraint trigger asserting per-transaction balance at commit,
append-only triggers on the journal, and account deletion blocked — all
proven with raw SQL probes that bypass the service layer.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: Migration V3 — role separation (TDD)

**Files:**
- Create: `src/main/resources/db/migration/V3__ledger_roles.sql`
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerRolesTest.java`

**Interfaces:**
- Consumes: V1 + V2 (Tasks 7-8), `IntegrationTestBase.appConnection()` (Task 2).
- Produces: role `nummus_app` (NOLOGIN by default; tests enable login via `ALTER ROLE`) with `SELECT` on all ledger tables, `INSERT` on all three, column-level `UPDATE (status, closed_at)` on `ledger_account` only, and `USAGE` on sequences.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

class LedgerRolesTest extends IntegrationTestBase {

  private static final String APP_PASSWORD = "nummus-app-test";

  @BeforeAll
  static void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanReadInsertAndColumnUpdateAccounts() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + accountPublicId + "', 'role test', 'LIABILITY')");
      st.executeUpdate("UPDATE ledger.ledger_account SET status = 'FROZEN' WHERE public_id = '"
          + accountPublicId + "'");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'")) {
        assertTrue(rs.next());
        assertTrue("FROZEN".equals(rs.getString(1)));
      }
    }
  }

  @Test
  void appRoleCannotMutateJournalOrOtherAccountColumns() throws Exception {
    String accountPublicId = UUID.randomUUID().toString();
    try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + accountPublicId + "', 'denied test', 'ASSET')");
    }
    try (Connection app = appConnection(); Statement st = app.createStatement()) {
      assertDenied(st, "UPDATE ledger.journal_posting SET amount = 1.0000");
      assertDenied(st, "DELETE FROM ledger.journal_transaction");
      assertDenied(st, "UPDATE ledger.ledger_account SET name = 'renamed' WHERE public_id = '"
          + accountPublicId + "'");
      assertDenied(st, "DELETE FROM ledger.ledger_account WHERE public_id = '" + accountPublicId + "'");
    }
  }

  @Test
  void appRoleCanCommitABalancedTransaction() throws Exception {
    String assetId = UUID.randomUUID().toString();
    String liabilityId = UUID.randomUUID().toString();
    try (Connection admin = adminConnection(); Statement st = admin.createStatement()) {
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + assetId + "', 'app asset', 'ASSET')");
      st.executeUpdate("INSERT INTO ledger.ledger_account (public_id, name, type) VALUES ('"
          + liabilityId + "', 'app liability', 'LIABILITY')");
    }
    assertDoesNotThrow(() -> {
      try (Connection app = appConnection()) {
        app.setAutoCommit(false);
        String txId = UUID.randomUUID().toString();
        try (Statement st = app.createStatement()) {
          st.executeUpdate("INSERT INTO ledger.journal_transaction (public_id, memo) VALUES ('"
              + txId + "', 'app role post')");
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, 'DEBIT', 7.5000 FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txId + "' AND a.public_id = '" + assetId + "'");
          st.executeUpdate("INSERT INTO ledger.journal_posting (transaction_id, account_id, direction, amount) "
              + "SELECT t.id, a.id, 'CREDIT', 7.5000 FROM ledger.journal_transaction t, ledger.ledger_account a "
              + "WHERE t.public_id = '" + txId + "' AND a.public_id = '" + liabilityId + "'");
        }
        app.commit();
      }
    });
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

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw test -Dtest=LedgerRolesTest
```

Expected: FAIL in `@BeforeAll` with `role "nummus_app" does not exist` (V3 not written yet).

- [ ] **Step 3: Write `V3__ledger_roles.sql`**

```sql
-- Layer 3 of invariant enforcement: least privilege. The application role
-- holds no UPDATE or DELETE on journal tables, so immutability does not
-- depend on application discipline. NOLOGIN by default: deployments enable
-- login with their own credentials out-of-band.

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'nummus_app') then
    create role nummus_app nologin;
  end if;
end
$$;

grant usage on schema ledger to nummus_app;

grant select on all tables in schema ledger to nummus_app;
grant insert on ledger.ledger_account, ledger.journal_transaction, ledger.journal_posting
  to nummus_app;

-- Status transitions are legitimate account operations; every other column is immutable.
grant update (status, closed_at) on ledger.ledger_account to nummus_app;

grant usage on all sequences in schema ledger to nummus_app;

alter default privileges in schema ledger
  grant select, insert on tables to nummus_app;
```

- [ ] **Step 4: Run to verify it passes**

```bash
./mvnw test -Dtest=LedgerRolesTest
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V3__ledger_roles.sql src/test/java/com/leandrossb/nummus/ledger/LedgerRolesTest.java
git commit -m "feat: add least-privilege application role

nummus_app holds SELECT/INSERT plus column-level account status updates;
journal mutation privileges do not exist for it at all.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 10: `JdbcClientLedgerRepository` — account operations (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/ledger/infrastructure/JdbcClientLedgerRepository.java`
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerRepositoryAccountsTest.java`

**Interfaces:**
- Consumes: `LedgerRepository` port (Task 5), migrations V1-V3 (Tasks 7-9).
- Produces: `@Repository class JdbcClientLedgerRepository implements LedgerRepository`, constructor `JdbcClientLedgerRepository(JdbcClient jdbc)`. This task implements `insertAccount`, `findAccount`, `updateAccountStatus`, and the shared `mapAccount` row mapper; `insertTransaction`, `findTransaction`, `rawBalance`, `statementLines` come in Task 11 (leave them as `throw new UnsupportedOperationException("implemented in Task 11")` stubs so the class compiles).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerRepositoryAccountsTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private com.leandrossb.nummus.ledger.application.LedgerRepository repository;

  @Test
  void insertAndFindAccountRoundTrips() {
    var account = new LedgerAccount(UUID.randomUUID(), "merchant payable",
        AccountType.LIABILITY, BRL, AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);

    var found = repository.findAccount(account.publicId()).orElseThrow();
    assertEquals(account.publicId(), found.publicId());
    assertEquals("merchant payable", found.name());
    assertEquals(AccountType.LIABILITY, found.type());
    assertEquals(BRL, found.currency());
    assertEquals(AccountStatus.ACTIVE, found.status());
    assertTrue(found.closedAt() == null);
  }

  @Test
  void findAccountReturnsEmptyForUnknownId() {
    assertTrue(repository.findAccount(UUID.randomUUID()).isEmpty());
  }

  @Test
  void updateAccountStatusTransitionsAndRecordsCloseTime() {
    var account = new LedgerAccount(UUID.randomUUID(), "transient", AccountType.ASSET,
        BRL, AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);

    assertTrue(repository.updateAccountStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repository.findAccount(account.publicId()).orElseThrow().status());

    Instant closedAt = Instant.now();
    assertTrue(repository.updateAccountStatus(account.publicId(), AccountStatus.CLOSED, closedAt));
    var closed = repository.findAccount(account.publicId()).orElseThrow();
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertTrue(closed.closedAt() != null);
    assertEquals(closedAt.toEpochMilli() / 1000, closed.closedAt().toEpochMilli() / 1000);

    assertTrue(!repository.updateAccountStatus(UUID.randomUUID(), AccountStatus.FROZEN, null));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw test -Dtest=LedgerRepositoryAccountsTest
```

Expected: FAIL — no bean implements `LedgerRepository` (context startup error: required bean not found).

- [ ] **Step 3: Implement**

```java
package com.leandrossb.nummus.ledger.infrastructure;

import com.leandrossb.nummus.ledger.application.LedgerRepository;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientLedgerRepository implements LedgerRepository {

  private final JdbcClient jdbc;

  public JdbcClientLedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public LedgerAccount insertAccount(LedgerAccount account) {
    jdbc.sql("""
        insert into ledger.ledger_account (public_id, name, type, currency, status, opened_at, closed_at)
        values (:publicId, :name, :type, :currency, :status, :openedAt, :closedAt)
        """)
        .param("publicId", account.publicId())
        .param("name", account.name())
        .param("type", account.type().name())
        .param("currency", account.currency().getCurrencyCode())
        .param("status", account.status().name())
        .param("openedAt", toOffsetDateTime(account.openedAt()))
        .param("closedAt", account.closedAt() == null ? null : toOffsetDateTime(account.closedAt()))
        .update();
    return account;
  }

  @Override
  public Optional<LedgerAccount> findAccount(UUID publicId) {
    return jdbc.sql("""
        select public_id, name, type, currency, status, opened_at, closed_at
        from ledger.ledger_account where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapAccount(rs))
        .optional();
  }

  @Override
  public boolean updateAccountStatus(UUID publicId, AccountStatus status, Instant closedAt) {
    int updated = jdbc.sql("""
        update ledger.ledger_account set status = :status, closed_at = :closedAt
        where public_id = :publicId
        """)
        .param("status", status.name())
        .param("closedAt", closedAt == null ? null : toOffsetDateTime(closedAt))
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  // ------------------------------------------------------------------
  // Journal operations: implemented in Task 11.

  @Override
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    throw new UnsupportedOperationException("journal writes arrive with Task 11");
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    throw new UnsupportedOperationException("journal reads arrive with Task 11");
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    throw new UnsupportedOperationException("balance derivation arrives with Task 11");
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    throw new UnsupportedOperationException("statements arrive with Task 11");
  }

  // ------------------------------------------------------------------

  private LedgerAccount mapAccount(ResultSet rs) throws SQLException {
    OffsetDateTime closedAt = rs.getObject("closed_at", OffsetDateTime.class);
    return new LedgerAccount(
        rs.getObject("public_id", UUID.class),
        rs.getString("name"),
        AccountType.valueOf(rs.getString("type")),
        Currency.getInstance(rs.getString("currency").trim()),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getObject("opened_at", OffsetDateTime.class).toInstant(),
        closedAt == null ? null : closedAt.toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
./mvnw test -Dtest=LedgerRepositoryAccountsTest
```

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/infrastructure/ src/test/java/com/leandrossb/nummus/ledger/LedgerRepositoryAccountsTest.java
git commit -m "feat: add JdbcClient repository for ledger accounts

Record-based mapping of accounts with enum and timestamptz handling;
journal methods stubbed pending Task 11.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 11: `JdbcClientLedgerRepository` — journal operations (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/ledger/infrastructure/JdbcClientLedgerRepository.java` (replace the four stubs)
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerRepositoryJournalTest.java`

**Interfaces:**
- Consumes: `JdbcClientLedgerRepository` account methods (Task 10) for fixtures, migrations V1-V3.
- Produces: the four remaining `LedgerRepository` methods, implemented as specified below. `LedgerServiceImpl` (Task 6) needs no changes — the port is already implemented against.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.application.LedgerRepository;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerRepositoryJournalTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private LedgerRepository repository;

  private LedgerAccount newAccount(String name, AccountType type) {
    var account = new LedgerAccount(UUID.randomUUID(), name, type, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);
    return account;
  }

  @Test
  void insertAndFindTransactionRoundTrip() {
    var asset = newAccount("journal asset", AccountType.ASSET);
    var liability = newAccount("journal liability", AccountType.LIABILITY);
    var posted = repository.insertTransaction("round trip", null, List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("12.3456")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("12.3456"))));

    var found = repository.findTransaction(posted.publicId()).orElseThrow();
    assertEquals(posted.publicId(), found.publicId());
    assertEquals("round trip", found.memo());
    assertEquals(2, found.postings().size());
    PostedPosting first = found.postings().get(0);
    assertEquals(asset.publicId(), first.accountPublicId());
    assertEquals(Direction.DEBIT, first.direction());
    assertEquals(0, first.amount().compareTo(Money.ofBrl("12.3456")));
    assertTrue(found.reversalOf() == null);
    assertTrue(found.bookedAt() != null);
  }

  @Test
  void rawBalanceSumsDebitsMinusCredits() {
    var asset = newAccount("balance asset", AccountType.ASSET);
    var liability = newAccount("balance liability", AccountType.LIABILITY);
    assertEquals(0, repository.rawBalance(asset.publicId()).compareTo(BigDecimal.ZERO));

    repository.insertTransaction("in", null, List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("100.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("100.0000"))));
    repository.insertTransaction("partial out", null, List.of(
        new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("30.0000")),
        new PostingDraft(liability.publicId(), Direction.DEBIT, Money.ofBrl("30.0000"))));

    assertEquals(0, repository.rawBalance(asset.publicId()).compareTo(new BigDecimal("70.0000")));
    assertEquals(0, repository.rawBalance(liability.publicId()).compareTo(new BigDecimal("-70.0000")));
  }

  @Test
  void statementLinesAreNewestFirstAndPaginated() throws Exception {
    var asset = newAccount("statement asset", AccountType.ASSET);
    var liability = newAccount("statement liability", AccountType.LIABILITY);
    for (int i = 1; i <= 3; i++) {
      repository.insertTransaction("tx-" + i, null, List.of(
          new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
          new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))));
      Thread.sleep(10); // distinct booked_at timestamps
    }
    var lines = repository.statementLines(asset.publicId(), 0, 2);
    assertEquals(2, lines.size());
    assertEquals("tx-3", lines.get(0).memo());
    assertEquals("tx-2", lines.get(1).memo());
    assertEquals(Direction.DEBIT, lines.get(0).direction());
    assertTrue(lines.get(0).transactionPublicId() != null);

    var rest = repository.statementLines(asset.publicId(), 2, 2);
    assertEquals(1, rest.size());
    assertEquals("tx-1", rest.get(0).memo());
  }

  @Test
  void duplicateReversalSurfacesAsDataIntegrityViolation() {
    var asset = newAccount("reversal asset", AccountType.ASSET);
    var liability = newAccount("reversal liability", AccountType.LIABILITY);
    var drafts = List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("5.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("5.0000")));
    var original = repository.insertTransaction("original", null, drafts);
    repository.insertTransaction("first reversal", original.publicId(), drafts);

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insertTransaction("second reversal", original.publicId(), drafts));
    // findTransaction exposes the reversal link as a public id
    var first = repository.findTransaction(
        repository.insertTransaction("chain base", null, drafts).publicId()).orElseThrow();
    assertTrue(first.reversalOf() == null);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
./mvnw test -Dtest=LedgerRepositoryJournalTest
```

Expected: FAIL — stubs throw `UnsupportedOperationException`.

- [ ] **Step 3: Implement (replace the four stubs; keep the Task 10 methods and helpers)**

New imports to add: `com.leandrossb.nummus.ledger.domain.Direction`, `com.leandrossb.nummus.ledger.domain.Money`, `com.leandrossb.nummus.ledger.domain.PostedPosting`.

```java
  @Override
  public PostedTransaction insertTransaction(String memo, UUID reversalOfPublicId,
      List<PostingDraft> postings) {
    UUID txPublicId = UUID.randomUUID();
    Instant bookedAt = Instant.now();
    Long txInternalId;
    if (reversalOfPublicId == null) {
      txInternalId = jdbc.sql("""
          insert into ledger.journal_transaction (public_id, memo, booked_at)
          values (:publicId, :memo, :bookedAt)
          returning id
          """)
          .param("publicId", txPublicId)
          .param("memo", memo)
          .param("bookedAt", toOffsetDateTime(bookedAt))
          .query((rs, i) -> rs.getLong(1))
          .single();
    } else {
      txInternalId = jdbc.sql("""
          insert into ledger.journal_transaction (public_id, memo, booked_at, reversal_of)
          values (:publicId, :memo, :bookedAt,
                  (select id from ledger.journal_transaction where public_id = :reversalOf))
          returning id
          """)
          .param("publicId", txPublicId)
          .param("memo", memo)
          .param("bookedAt", toOffsetDateTime(bookedAt))
          .param("reversalOf", reversalOfPublicId)
          .query((rs, i) -> rs.getLong(1))
          .single();
    }
    for (PostingDraft draft : postings) {
      int inserted = jdbc.sql("""
          insert into ledger.journal_posting (transaction_id, account_id, direction, amount)
          select :txInternalId, a.id, :direction, :amount
          from ledger.ledger_account a
          where a.public_id = :accountPublicId
          """)
          .param("txInternalId", txInternalId)
          .param("direction", draft.direction().name())
          .param("amount", draft.amount().amount())
          .param("accountPublicId", draft.accountPublicId())
          .update();
      if (inserted != 1) {
        throw new com.leandrossb.nummus.ledger.domain.UnknownAccountException(draft.accountPublicId());
      }
    }
    List<PostedPosting> posted = postings.stream()
        .map(d -> new PostedPosting(d.accountPublicId(), d.direction(), d.amount()))
        .toList();
    return new PostedTransaction(txPublicId, memo, bookedAt, reversalOfPublicId, posted);
  }

  @Override
  public Optional<PostedTransaction> findTransaction(UUID publicId) {
    record Row(UUID accountPublicId, Direction direction, BigDecimal amount) {}
    List<Row> rows = jdbc.sql("""
        select a.public_id as account_public_id, p.direction, p.amount
        from ledger.journal_transaction t
        left join ledger.journal_transaction r on r.id = t.reversal_of
        join ledger.journal_posting p on p.transaction_id = t.id
        join ledger.ledger_account a on a.id = p.account_id
        where t.public_id = :publicId
        order by p.id
        """)
        .param("publicId", publicId)
        .query((rs, i) -> new Row(
            rs.getObject("account_public_id", UUID.class),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount")))
        .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return jdbc.sql("""
        select t.public_id, t.memo, t.booked_at, r.public_id as reversal_public_id
        from ledger.journal_transaction t
        left join ledger.journal_transaction r on r.id = t.reversal_of
        where t.public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> {
          PostedTransaction tx = new PostedTransaction(
              rs.getObject("public_id", UUID.class),
              rs.getString("memo"),
              rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
              rs.getObject("reversal_public_id", UUID.class),
              rows.stream()
                  .map(row -> new PostedPosting(row.accountPublicId(), row.direction(),
                      Money.of(row.amount(), Currency.getInstance("BRL"))))
                  .toList());
          return tx;
        })
        .optional();
  }

  @Override
  public BigDecimal rawBalance(UUID accountPublicId) {
    return jdbc.sql("""
        select coalesce(sum(case p.direction when 'DEBIT' then p.amount else -p.amount end), 0) as balance
        from ledger.journal_posting p
        where p.account_id = (select id from ledger.ledger_account where public_id = :publicId)
        """)
        .param("publicId", accountPublicId)
        .query((rs, i) -> rs.getBigDecimal("balance"))
        .single();
  }

  @Override
  public List<StatementLine> statementLines(UUID accountPublicId, int offset, int limit) {
    return jdbc.sql("""
        select t.booked_at, t.public_id as transaction_public_id, t.memo, p.direction, p.amount
        from ledger.journal_posting p
        join ledger.journal_transaction t on t.id = p.transaction_id
        where p.account_id = (select id from ledger.ledger_account where public_id = :publicId)
        order by t.booked_at desc, p.id desc
        limit :limit offset :offset
        """)
        .param("publicId", accountPublicId)
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, i) -> new StatementLine(
            rs.getObject("booked_at", OffsetDateTime.class).toInstant(),
            rs.getObject("transaction_public_id", UUID.class),
            rs.getString("memo"),
            Direction.valueOf(rs.getString("direction")),
            Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL"))))
        .list();
  }
```

Also add `com.leandrossb.nummus.ledger.domain.StatementLine` is already imported from Task 10; ensure `Direction`, `Money`, `PostedPosting` imports are present.

- [ ] **Step 4: Run to verify they pass**

```bash
./mvnw test -Dtest='LedgerRepositoryJournalTest,LedgerRepositoryAccountsTest'
```

Expected: PASS (4 + 3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ledger/infrastructure/JdbcClientLedgerRepository.java src/test/java/com/leandrossb/nummus/ledger/LedgerRepositoryJournalTest.java
git commit -m "feat: add journal writes, reads, and derived balances

Transactional insert with account resolution, reversal linkage, balance
aggregation over the covering index, and newest-first statements.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 12: Full service flow end-to-end (integration)

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerServiceIntegrationTest.java`

**Interfaces:**
- Consumes: the wired `Ledger` bean (`LedgerServiceImpl` + `JdbcClientLedgerRepository`), migrations V1-V3.
- Produces: no production code — this task proves the composed stack against the real database (the unit tests of Task 6 used the fake).

- [ ] **Step 1: Write the test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerServiceIntegrationTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private Ledger ledger;

  @Test
  void postingCycleWithReversalRestoresBalances() {
    var asset = ledger.openAccount(new OpenAccountCommand("e2e cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(new OpenAccountCommand("e2e payable", AccountType.LIABILITY, BRL));
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0")));

    var tx = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));

    var reversal = ledger.reverse(tx.publicId(), "undo funding");
    assertEquals(tx.publicId(), reversal.reversalOf());
    assertEquals(Direction.CREDIT, reversal.postings().get(0).direction());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0")));

    assertThrows(TransactionAlreadyReversedException.class, () -> ledger.reverse(tx.publicId(), "again"));
    var redo = ledger.reverse(reversal.publicId(), "redo funding");
    assertNotNull(redo.reversalOf());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void lifecycleGuardsHoldAgainstRealDatabase() {
    var asset = ledger.openAccount(new OpenAccountCommand("guard cash", AccountType.ASSET, BRL));
    var counterparty = ledger.openAccount(new OpenAccountCommand("guard cp", AccountType.LIABILITY, BRL));
    var drafts = List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(counterparty.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")));

    ledger.freezeAccount(asset.publicId());
    assertThrows(AccountNotActiveException.class,
        () -> ledger.post(new PostTransactionCommand("frozen", drafts)));

    ledger.closeAccount(counterparty.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.closeAccount(counterparty.publicId()));

    assertThrows(UnknownAccountException.class, () -> ledger.balance(UUID.randomUUID()));
    assertThrows(UnknownTransactionException.class, () -> ledger.getTransaction(UUID.randomUUID()));
  }

  @Test
  void statementPairsPostingsWithDerivedBalance() {
    var asset = ledger.openAccount(new OpenAccountCommand("stmt cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(new OpenAccountCommand("stmt cp", AccountType.LIABILITY, BRL));
    for (int i = 1; i <= 3; i++) {
      ledger.post(new PostTransactionCommand("stmt-" + i, List.of(
          new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("2.0000")),
          new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("2.0000")))));
    }
    var statement = ledger.statement(asset.publicId(), new Page(0, 2));
    assertEquals(asset.publicId(), statement.account().publicId());
    assertEquals(0, statement.balance().compareTo(Money.ofBrl("6.0000")));
    assertEquals(2, statement.lines().size());
    assertEquals("stmt-3", statement.lines().get(0).memo());
    assertTrue(statement.lines().get(0).bookedAt() != null);
  }
}
```

- [ ] **Step 2: Run it**

```bash
./mvnw test -Dtest=LedgerServiceIntegrationTest
```

Expected: PASS (3 tests). If `post` fails with the deferred trigger's "unbalanced" message, the transaction was never balanced in the first place — check the drafts. If reversal fails with `TransactionAlreadyReversedException` on first attempt, the unique constraint caught a stale `reversal_of` — check `insertTransaction`'s subselect.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/ledger/LedgerServiceIntegrationTest.java
git commit -m "test: prove ledger service end-to-end against PostgreSQL

Posting cycle with reversal and re-reversal, lifecycle guards, and
statement pairing under the real trigger-enforced database.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 13: Concurrency — parallel posts converge exactly

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/ledger/LedgerConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: wired `Ledger` bean.
- Produces: no production code. This is the spec's concurrency proof: parallel writers append postings without contending for correctness; the final balance is the exact sum.

- [ ] **Step 1: Write the test**

```java
package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerConcurrencyIntegrationTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final int THREADS = 8;
  private static final int POSTS_PER_THREAD = 25;

  @Autowired
  private Ledger ledger;

  @Test
  @Timeout(120)
  void concurrentPostsAllCommitAndBalanceConvergesExactly() throws Exception {
    var asset = ledger.openAccount(new OpenAccountCommand("concurrency cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(
        new OpenAccountCommand("concurrency payable", AccountType.LIABILITY, BRL));

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          for (int j = 0; j < POSTS_PER_THREAD; j++) {
            ledger.post(new PostTransactionCommand("concurrent", List.of(
                new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
                new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(90, TimeUnit.SECONDS); // rethrows the first worker failure, if any
      }
    } finally {
      pool.shutdownNow();
    }

    int expectedPosts = THREADS * POSTS_PER_THREAD;
    assertEquals(0, ledger.balance(asset.publicId())
        .compareTo(Money.ofBrl(String.valueOf(expectedPosts) + ".0000")));
    assertEquals(0, ledger.balance(liability.publicId())
        .compareTo(Money.ofBrl("-" + expectedPosts + ".0000")));
  }
}
```

- [ ] **Step 2: Run it**

```bash
./mvnw test -Dtest=LedgerConcurrencyIntegrationTest
```

Expected: PASS. All 200 posts commit; balances are exactly `±200.0000`. Hikari's default pool (10 connections) covers 8 workers.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/ledger/LedgerConcurrencyIntegrationTest.java
git commit -m "test: prove concurrent posting converges to exact balances

Two hundred parallel balanced transactions on shared accounts commit
without serialization and derive exact final balances.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 14: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` (milestone checkbox)

**Interfaces:**
- Consumes: everything.
- Produces: the M1 success criteria evidence.

- [ ] **Step 1: Run the full build**

```bash
./mvnw verify
```

Expected: `BUILD SUCCESS`, all tests green. The suite must include the spec's success criteria: unbalanced transaction rejected via raw SQL (Task 8), journal immutable even to the owner (Task 8) and to the app role by privilege absence (Task 9), balances exact after reversals (Task 12), concurrency convergence (Task 13).

- [ ] **Step 2: Update `README.md`**

Change the milestone line:

```markdown
- [x] M1 — Ledger core: journal, invariants, derived balances
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: mark M1 ledger core complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 4: Report**

Report the final `./mvnw verify` summary line and the test count to the user. Do not claim success without the command output.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| Bootstrap & structure | 1, 2 |
| Domain model (`Money`, enums, records, exceptions) | 3, 4 |
| Internal API (`Ledger` port, non-idempotent by design) | 5, 6 |
| Schema `ledger` (tables, indexes, ids, currency-on-account) | 7 |
| Enforcement layer 1 (deferred balanced trigger) | 8 |
| Enforcement layer 2 (immutability triggers) | 8 |
| Enforcement layer 3 (role separation) | 9 |
| Repository adapter (JdbcClient) | 10, 11 |
| Reversals incl. double-reversal and chains | 6, 11, 12 |
| Derived balances & statements | 11, 12 |
| Concurrency convergence | 13 |
| Success criteria & README | 14 |

