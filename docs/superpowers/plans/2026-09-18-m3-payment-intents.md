# M3 Payment Intents and PSP Simulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the `payments` and `psp-simulator` modules — payment intents with lazy expiry, exactly-once lazy settlement into the ledger, and the DB-backed simulator that plays the external payment network.

**Architecture:** Two new hexagonal modules on the M1/M2 pattern. `payments` orchestrates `AccountsService` + `Ledger` ports and owns the `PaymentNetwork` port (the PSP contract); `psp-simulator` implements that port and exposes payer-facing REST. Settlement posts DEBIT clearing-asset / CREDIT merchant-payable in one transaction with status-guarded intent transitions. Spec: `docs/superpowers/specs/2026-09-18-m3-payment-intents-design.md`.

**Tech Stack:** unchanged from M2 — Java 25, Spring Boot 4.1.1, JdbcClient, Flyway V5+V6, Testcontainers 2.x, MockMvc (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` + `spring-boot-webmvc-test` test dep, already in the pom from M2).

## Global Constraints

- **Language:** English everywhere — code, identifiers, comments, commit messages. Public product repo: never demo/portfolio framing. No placeholder code.
- **Commits:** Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Money:** `BigDecimal` via `Money`, scale ≤ 4, BRL; compare with `compareTo`, never `equals`.
- **Module boundaries:** `payments` touches ledger/accounts ONLY via `..ledger.application..`/`..accounts.application..` + the domain vocabulary those ports expose; `psp-simulator` touches ONLY `..payments.application..`; nothing depends on `psp-simulator`; no runtime cross-module table access. Clearing-account seed is V5 migration data (composition layer), not runtime access.
- **Journal untouched:** M1-M4 migrations (V1-V4) are never modified. M3 adds V5 (payments + clearing seed) and V6 (psp_simulator).
- **Bean ordering (hard lesson):** the `PaymentNetwork` implementation bean (Task 3) lands BEFORE `PaymentsServiceImpl` (Task 5) — every `@SpringBootTest` context stays green at every commit.
- **Status-guarded transitions:** every intent/charge state change is a single-row conditional UPDATE with rowcount check; a lost settle race throws (rollback undoes the posting).
- **Test environment (this host):** remote Docker via controller-managed megalan tunnel. EVERY `./mvnw` test command MUST be prefixed with:
  ```
  DOCKER_HOST=unix:///tmp/megalan-docker.sock TESTCONTAINERS_HOST_OVERRIDE=192.168.0.210 TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test ...
  ```
  Do not touch the tunnel. Health check: `curl -s --unix-socket /tmp/megalan-docker.sock http://localhost/_ping` → `OK`; if it fails, report BLOCKED. `postgres:18-alpine` pre-pulled. No local docker CLI.
- **JDK:** if `./mvnw` reports a JDK error, prefix `JAVA_HOME=/home/legat/.jdks/jdk-25.0.4.1+1`.
- **API facts verified against M2:** `ProblemDetail.getStatus()` returns `int` (assert with `.value()`); `UncategorizedSQLException` ctor takes `SQLException`; handler registers the `DataAccessException` parent; `rootMessage` is depth-capped; ArchUnit uses `resideOutsideOfPackage`; new-package ArchUnit rules need non-empty selections (extend rules only in the task AFTER the packages exist — Task 7).
- **Test classes end in `Test`**; success criteria = `./mvnw verify` green (Task 9).
- Expected suite counts are targets — if a task legitimately adds/removes a test, report the delta; do not pad.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/
  V5__payments_schema.sql                             (Task 2)
  V6__psp_simulator_schema.sql                        (Task 3)
src/main/java/com/leandrossb/nummus/interfaces/
  GlobalExceptionHandler.java                         (Task 1, moved from accounts.interfaces)
src/main/java/com/leandrossb/nummus/payments/
  domain/PaymentIntent.java, IntentStatus.java, CreateIntentCommand.java,
        UnknownPaymentIntentException.java, ConcurrentSettlementException.java,
        ChargeAmountMismatchException.java            (Task 2)
  application/PaymentNetwork.java, NetworkCharge.java, ChargeStatus.java,
        PaymentClearingAccount.java                   (Task 2)
  application/PaymentsService.java, PaymentsRepository.java   (Tasks 2, 4)
  application/PaymentsServiceImpl.java               (Task 5)
  infrastructure/JdbcClientPaymentsRepository.java    (Task 4)
  interfaces/PaymentsController.java                  (Task 6)
  interfaces/dto/CreateIntentRequest.java, IntentResponse.java (Task 6)
src/main/java/com/leandrossb/nummus/psp_simulator/
  domain/SimulatedCharge.java, UnknownChargeException.java,
        ChargeNotPendingException.java                (Task 3)
  application/SimulatorService.java, SimulatorServiceImpl.java (Task 3)
  infrastructure/JdbcClientChargeStore.java           (Task 3)
  infrastructure/SimulatorPaymentNetwork.java         (Task 3, implements PaymentNetwork)
  interfaces/SimulatorController.java                 (Task 3)
  interfaces/dto/ChargeResponse.java                  (Task 3)
src/test/java/com/leandrossb/nummus/
  interfaces/GlobalExceptionHandlerTest.java          (Task 1, moved)
  payments/PaymentsSchemaTest.java                    (Task 2)
  psp_simulator/SimulatorSchemaTest.java, SimulatorRestApiTest.java (Task 3)
  payments/PaymentsRepositoryTest.java                (Task 4)
  payments/application/InMemoryPaymentsRepository.java,
        FakePaymentNetwork.java, PaymentsServiceImplTest.java   (Task 5)
  payments/PaymentsRestApiTest.java                   (Task 6)
  architecture/ModuleBoundaryTest.java                (Task 7, extended)
  payments/PaymentSettlementConcurrencyTest.java      (Task 7)
README.md, docs/m2-backlog.md                        (Task 9)
```

---

### Task 1: Relocate the error advice to a shared package

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Delete: `src/main/java/com/leandrossb/nummus/accounts/interfaces/GlobalExceptionHandler.java`
- Move: `src/test/java/com/leandrossb/nummus/accounts/GlobalExceptionHandlerTest.java` → `src/test/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: M2 handler (unchanged behavior).
- Produces: `com.leandrossb.nummus.interfaces.GlobalExceptionHandler` — the app-wide advice location Tasks 3/6 extend with simulator/payments vocabulary. This is the M2-backlog scoping item: a second module's controllers now exist.

- [ ] **Step 1: Move the handler**

Copy `GlobalExceptionHandler.java` from `accounts.interfaces` to `com.leandrossb.nummus.interfaces`, changing ONLY the package declaration (and make the class javadoc say it is the application-wide REST error surface). Delete the old file. Do not change any method, registration, or message.

- [ ] **Step 2: Move the test**

Move `GlobalExceptionHandlerTest.java` to `com.leandrossb.nummus.interfaces` (package declaration updated; imports of `accounts.domain`/`accounts.interfaces` exceptions stay — the handler still maps them). Remove any now-unused imports the move creates.

- [ ] **Step 3: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=GlobalExceptionHandlerTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused 5/5; full suite 88/88 (a pure move — same counts).

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/
git commit -m "refactor: move REST error advice to a shared interfaces package

The advice is application-wide now that a second module's
controllers are arriving; behavior is unchanged.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: Payments domain, PaymentNetwork port, and V5 (TDD)

**Files:**
- Create in `src/main/java/com/leandrossb/nummus/payments/domain/`: `PaymentIntent.java`, `IntentStatus.java`, `CreateIntentCommand.java`, `UnknownPaymentIntentException.java`, `ConcurrentSettlementException.java`, `ChargeAmountMismatchException.java`
- Create in `src/main/java/com/leandrossb/nummus/payments/application/`: `PaymentNetwork.java`, `NetworkCharge.java`, `ChargeStatus.java`, `PaymentClearingAccount.java`
- Create: `src/main/resources/db/migration/V5__payments_schema.sql`
- Create: `src/test/java/com/leandrossb/nummus/payments/PaymentsSchemaTest.java`

**Interfaces:**
- Consumes: M1 `Money`.
- Produces (used by Tasks 3-7):
  - `enum IntentStatus { CREATED, SETTLED, FAILED, EXPIRED }`
  - `record PaymentIntent(UUID publicId, UUID accountPublicId, Money amount, IntentStatus status, UUID chargePublicId, Instant expiresAt, Instant createdAt, Instant settledAt, UUID journalTransactionPublicId)`
  - `record CreateIntentCommand(UUID accountPublicId, Money amount, Duration ttl)`
  - Exceptions: `UnknownPaymentIntentException(UUID publicId)`, `ConcurrentSettlementException(UUID publicId)`, `ChargeAmountMismatchException(UUID chargePublicId, Money expected, Money actual)`
  - `interface PaymentNetwork { NetworkCharge createCharge(Money amount); NetworkCharge getCharge(UUID chargePublicId); }`
  - `record NetworkCharge(UUID publicId, Money amount, ChargeStatus status)`, `enum ChargeStatus { PENDING, SUCCEEDED, FAILED }`
  - `PaymentClearingAccount.PUBLIC_ID` — the fixed clearing UUID seeded by V5
  - schema `payments` with `payment_intent` + the clearing ledger row

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentsSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsIntentRowsAndSeedsTheClearingAccount() throws Exception {
    String intentId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO payments.payment_intent (public_id, account_public_id, amount, charge_public_id, expires_at) "
          + "VALUES ('" + intentId + "', '" + UUID.randomUUID() + "', 10.0000, '" + UUID.randomUUID() + "', now() + interval '30 minutes')");
      try (ResultSet rs = st.executeQuery(
          "SELECT status, journal_transaction_public_id FROM payments.payment_intent WHERE public_id = '" + intentId + "'")) {
        assertTrue(rs.next());
        assertEquals("CREATED", rs.getString(1));
        assertTrue(rs.getObject(2) == null);
      }
      try (ResultSet rs = st.executeQuery(
          "SELECT type, currency, status FROM ledger.ledger_account WHERE public_id = '5f9c3b2e-0000-4000-8000-000000000001'")) {
        assertTrue(rs.next());
        assertEquals("ASSET", rs.getString(1));
        assertEquals("BRL", rs.getString(2).trim());
        assertEquals("ACTIVE", rs.getString(3));
      }
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsSchemaTest
```

Expected: FAIL — `schema "payments" does not exist` (or the clearing row missing).

- [ ] **Step 3: Write the domain types and port**

`IntentStatus.java`:

```java
package com.leandrossb.nummus.payments.domain;

/** Payment intent lifecycle: CREATED is the only mutable state; the rest are terminal. */
public enum IntentStatus {
  CREATED,
  SETTLED,
  FAILED,
  EXPIRED
}
```

`PaymentIntent.java`:

```java
package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's instant-payment intent. References the merchant's payment account and the
 * network charge by public UUID only; {@code journalTransactionPublicId} links the exactly-once
 * settlement entry once posted.
 */
public record PaymentIntent(
    UUID publicId,
    UUID accountPublicId,
    Money amount,
    IntentStatus status,
    UUID chargePublicId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt,
    UUID journalTransactionPublicId) {
}
```

`CreateIntentCommand.java`:

```java
package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Duration;
import java.util.UUID;

/** Command to create a payment intent. A null ttl selects the default expiry window. */
public record CreateIntentCommand(UUID accountPublicId, Money amount, Duration ttl) {
}
```

`UnknownPaymentIntentException.java`:

```java
package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/** Thrown when a payment intent id does not exist. */
public class UnknownPaymentIntentException extends RuntimeException {

  public UnknownPaymentIntentException(UUID publicId) {
    super("unknown payment intent: " + publicId);
  }
}
```

`ConcurrentSettlementException.java`:

```java
package com.leandrossb.nummus.payments.domain;

import java.util.UUID;

/**
 * Thrown when a settle attempt loses the status-guarded transition race. Its transaction —
 * including the journal posting — rolled back; re-reading the intent shows SETTLED.
 */
public class ConcurrentSettlementException extends RuntimeException {

  public ConcurrentSettlementException(UUID publicId) {
    super("payment intent " + publicId + " was settled concurrently; re-read the current state");
  }
}
```

`ChargeAmountMismatchException.java`:

```java
package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** Thrown when the network echoes a charge amount different from the intent's — an invariant breach. */
public class ChargeAmountMismatchException extends RuntimeException {

  public ChargeAmountMismatchException(UUID chargePublicId, Money expected, Money actual) {
    super("charge " + chargePublicId + " amount mismatch: expected "
        + expected.amount().toPlainString() + " got " + actual.amount().toPlainString());
  }
}
```

`ChargeStatus.java` (payments.application — the network vocabulary):

```java
package com.leandrossb.nummus.payments.application;

/** Charge state as seen at the external payment network. */
public enum ChargeStatus {
  PENDING,
  SUCCEEDED,
  FAILED
}
```

`NetworkCharge.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** A charge at the external network. The amount is echoed so callers can detect mutation. */
public record NetworkCharge(UUID publicId, Money amount, ChargeStatus status) {
}
```

`PaymentNetwork.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * The external payment network contract — the same interface a real PSP adapter would
 * implement. The psp-simulator module provides the implementation; production code never
 * mocks this boundary.
 */
public interface PaymentNetwork {

  NetworkCharge createCharge(Money amount);

  NetworkCharge getCharge(UUID chargePublicId);
}
```

`PaymentClearingAccount.java`:

```java
package com.leandrossb.nummus.payments.application;

import java.util.UUID;

/**
 * The system clearing asset the V5 migration seeds — money the payment network owes us.
 * Seeded by migration (the composition layer) with a fixed public id; runtime code never
 * writes to the ledger schema directly.
 */
public final class PaymentClearingAccount {

  public static final UUID PUBLIC_ID = UUID.fromString("5f9c3b2e-0000-4000-8000-000000000001");

  private PaymentClearingAccount() {
  }
}
```

- [ ] **Step 4: Write `V5__payments_schema.sql`**

```sql
-- M3 payments: module-owned schema for payment intents, plus the clearing-asset
-- seed row in the ledger schema. The single Flyway chain is the modular monolith's
-- composition layer: the seed is deployment data, not runtime cross-module access.

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

- [ ] **Step 5: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsSchemaTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS; full suite 89/89.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/ src/main/resources/db/migration/V5__payments_schema.sql src/test/java/com/leandrossb/nummus/payments/
git commit -m "feat: add payments domain, network port, and schema

Payment intent records, the PaymentNetwork contract a real PSP
adapter would implement, and V5 with the clearing-asset seed.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: PSP simulator module end-to-end (TDD)

**Files:**
- Create in `src/main/java/com/leandrossb/nummus/psp_simulator/domain/`: `SimulatedCharge.java`, `UnknownChargeException.java`, `ChargeNotPendingException.java`
- Create in `src/main/java/com/leandrossb/nummus/psp_simulator/application/`: `SimulatorService.java`, `SimulatorServiceImpl.java`
- Create in `src/main/java/com/leandrossb/nummus/psp_simulator/infrastructure/`: `JdbcClientChargeStore.java`, `SimulatorPaymentNetwork.java`
- Create in `src/main/java/com/leandrossb/nummus/psp_simulator/interfaces/`: `SimulatorController.java`, `dto/ChargeResponse.java`
- Create: `src/main/resources/db/migration/V6__psp_simulator_schema.sql`
- Create: `src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorSchemaTest.java`, `src/test/java/com/leandrossb/nummus/psp_simulator/SimulatorRestApiTest.java`

**Interfaces:**
- Consumes: `PaymentNetwork`/`NetworkCharge`/`ChargeStatus` (Task 2), `Money`.
- Produces (used by Tasks 5-7): the `SimulatorPaymentNetwork` bean implementing `PaymentNetwork`; REST `POST /simulator/charges/{id}/pay|fail`, `GET /simulator/charges/{id}`; `interface SimulatorService { NetworkCharge create(Money); NetworkCharge get(UUID); NetworkCharge pay(UUID); NetworkCharge fail(UUID); }`; charge store port `interface ChargeStore { SimulatedCharge insert(SimulatedCharge); Optional<SimulatedCharge> findByPublicId(UUID); boolean transition(UUID publicId, ChargeStatus target); }`.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.psp_simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimulatorSchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsChargeRowsWithDefaults() throws Exception {
    String chargeId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO psp_simulator.charge (public_id, amount) "
          + "VALUES ('" + chargeId + "', 25.5000)");
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM psp_simulator.charge WHERE public_id = '" + chargeId + "'")) {
        assertTrue(rs.next());
        assertEquals("PENDING", rs.getString(1));
      }
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=SimulatorSchemaTest
```

Expected: FAIL — schema `psp_simulator` does not exist.

- [ ] **Step 3: Write `V6__psp_simulator_schema.sql`**

```sql
-- M3 psp-simulator: module-owned schema playing the external payment network.
-- Charges persist like a real network's state would — this is what M6 conciliation
-- will match settlement reports against.

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

- [ ] **Step 4: Run to verify it passes**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=SimulatorSchemaTest
```

Expected: PASS.

- [ ] **Step 5: Implement the module**

`SimulatedCharge.java`:

```java
package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.time.Instant;
import java.util.UUID;

/** A charge as held by the simulated network. */
public record SimulatedCharge(
    UUID publicId, Money amount, ChargeStatus status, Instant createdAt, Instant updatedAt) {
}
```

`UnknownChargeException.java`:

```java
package com.leandrossb.nummus.psp_simulator.domain;

import java.util.UUID;

/** Thrown when a charge id does not exist at the simulated network. */
public class UnknownChargeException extends RuntimeException {

  public UnknownChargeException(UUID publicId) {
    super("unknown charge: " + publicId);
  }
}
```

`ChargeNotPendingException.java`:

```java
package com.leandrossb.nummus.psp_simulator.domain;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import java.util.UUID;

/** Thrown when a payer action targets a charge that is no longer PENDING. */
public class ChargeNotPendingException extends RuntimeException {

  public ChargeNotPendingException(UUID publicId, ChargeStatus status) {
    super("charge " + publicId + " is not PENDING: " + status);
  }
}
```

`ChargeStore.java` (psp_simulator.application):

```java
package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the simulated network. Transitions are status-guarded. */
public interface ChargeStore {

  SimulatedCharge insert(SimulatedCharge charge);

  Optional<SimulatedCharge> findByPublicId(UUID publicId);

  /** @return false when the charge does not exist or is not PENDING. */
  boolean transition(UUID publicId, ChargeStatus target);
}
```

`SimulatorService.java`:

```java
package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import java.util.UUID;

/** The simulated network's own operations: create, inspect, and act as the payer. */
public interface SimulatorService {

  NetworkCharge create(Money amount);

  NetworkCharge get(UUID publicId);

  /** Payer pays the charge: PENDING → SUCCEEDED (terminal). */
  NetworkCharge pay(UUID publicId);

  /** Payer abandons/fails the charge: PENDING → FAILED (terminal). */
  NetworkCharge fail(UUID publicId);
}
```

`SimulatorServiceImpl.java`:

```java
package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.psp_simulator.domain.ChargeNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import com.leandrossb.nummus.psp_simulator.domain.UnknownChargeException;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulatorServiceImpl implements SimulatorService {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final ChargeStore chargeStore;

  public SimulatorServiceImpl(ChargeStore chargeStore) {
    this.chargeStore = chargeStore;
  }

  @Override
  @Transactional
  public NetworkCharge create(Money amount) {
    Objects.requireNonNull(amount, "amount must not be null");
    var charge = chargeStore.insert(new SimulatedCharge(UUID.randomUUID(), amount,
        ChargeStatus.PENDING, Instant.now(), Instant.now()));
    return toNetworkCharge(charge);
  }

  @Override
  @Transactional(readOnly = true)
  public NetworkCharge get(UUID publicId) {
    return toNetworkCharge(require(publicId));
  }

  @Override
  @Transactional
  public NetworkCharge pay(UUID publicId) {
    return transition(publicId, ChargeStatus.SUCCEEDED);
  }

  @Override
  @Transactional
  public NetworkCharge fail(UUID publicId) {
    return transition(publicId, ChargeStatus.FAILED);
  }

  private NetworkCharge transition(UUID publicId, ChargeStatus target) {
    require(publicId);
    if (!chargeStore.transition(publicId, target)) {
      throw new ChargeNotPendingException(publicId, require(publicId).status());
    }
    return toNetworkCharge(require(publicId));
  }

  private SimulatedCharge require(UUID publicId) {
    return chargeStore.findByPublicId(publicId).orElseThrow(() -> new UnknownChargeException(publicId));
  }

  private static NetworkCharge toNetworkCharge(SimulatedCharge charge) {
    return new NetworkCharge(charge.publicId(), charge.amount(), charge.status());
  }
}
```

`JdbcClientChargeStore.java`:

```java
package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.psp_simulator.application.ChargeStore;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientChargeStore implements ChargeStore {

  private final JdbcClient jdbc;

  public JdbcClientChargeStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public SimulatedCharge insert(SimulatedCharge charge) {
    jdbc.sql("""
        insert into psp_simulator.charge (public_id, amount, status, created_at, updated_at)
        values (:publicId, :amount, :status, :createdAt, :updatedAt)
        """)
        .param("publicId", charge.publicId())
        .param("amount", charge.amount().amount())
        .param("status", charge.status().name())
        .param("createdAt", toOffsetDateTime(charge.createdAt()))
        .param("updatedAt", toOffsetDateTime(charge.updatedAt()))
        .update();
    return charge;
  }

  @Override
  public Optional<SimulatedCharge> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, amount, status, created_at, updated_at
        from psp_simulator.charge where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapCharge(rs))
        .optional();
  }

  @Override
  public boolean transition(UUID publicId, ChargeStatus target) {
    int updated = jdbc.sql("""
        update psp_simulator.charge set status = :status, updated_at = now()
        where public_id = :publicId and status = 'PENDING'
        """)
        .param("status", target.name())
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private SimulatedCharge mapCharge(ResultSet rs) throws SQLException {
    return new SimulatedCharge(
        rs.getObject("public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        ChargeStatus.valueOf(rs.getString("status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
```

`SimulatorPaymentNetwork.java` — the bean the payments module will consume (this task lands it BEFORE Task 5's `@Service`, per the bean-ordering constraint):

```java
package com.leandrossb.nummus.psp_simulator.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The simulated network as seen through the payments module's port — the same
 * bean shape a real PSP adapter would take.
 */
@Component
public class SimulatorPaymentNetwork implements PaymentNetwork {

  private final SimulatorService simulator;

  public SimulatorPaymentNetwork(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @Override
  public NetworkCharge createCharge(Money amount) {
    return simulator.create(amount);
  }

  @Override
  public NetworkCharge getCharge(UUID chargePublicId) {
    return simulator.get(chargePublicId);
  }
}
```

`dto/ChargeResponse.java`:

```java
package com.leandrossb.nummus.psp_simulator.interfaces.dto;

import com.leandrossb.nummus.payments.application.NetworkCharge;
import java.math.BigDecimal;
import java.util.UUID;

/** REST view of a simulated charge. */
public record ChargeResponse(UUID publicId, BigDecimal amount, String currency, String status) {

  public static ChargeResponse from(NetworkCharge charge) {
    return new ChargeResponse(charge.publicId(), charge.amount().amount(),
        charge.amount().currency().getCurrencyCode(), charge.status().name());
  }
}
```

`SimulatorController.java`:

```java
package com.leandrossb.nummus.psp_simulator.interfaces;

import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.psp_simulator.interfaces.dto.ChargeResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The external world's surface: create charges indirectly via PaymentNetwork, act as the payer here. */
@RestController
@RequestMapping("/simulator/charges")
class SimulatorController {

  private final SimulatorService simulator;

  SimulatorController(SimulatorService simulator) {
    this.simulator = simulator;
  }

  @GetMapping("/{id}")
  ChargeResponse get(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.get(id));
  }

  @PostMapping("/{id}/pay")
  ChargeResponse pay(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.pay(id));
  }

  @PostMapping("/{id}/fail")
  ChargeResponse fail(@PathVariable UUID id) {
    return ChargeResponse.from(simulator.fail(id));
  }
}
```

- [ ] **Step 6: Add the simulator exceptions to the shared advice**

In `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`, add imports `com.leandrossb.nummus.psp_simulator.domain.ChargeNotPendingException` and `com.leandrossb.nummus.psp_simulator.domain.UnknownChargeException`, then register them: `UnknownChargeException` joins the `notFound` handler's list; `ChargeNotPendingException` joins the `conflict` handler's list. Nothing else changes.

- [ ] **Step 7: Write the REST test**

```java
package com.leandrossb.nummus.psp_simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentNetwork;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SimulatorRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PaymentNetwork paymentNetwork;

  @Test
  void payerActionsTransitionPendingChargeAndSecondActionConflicts() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("25.5000"));

    mockMvc.perform(post("/simulator/charges/{id}/pay", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.amount").value(25.5000))
        .andExpect(jsonPath("$.currency").value("BRL"));

    mockMvc.perform(get("/simulator/charges/{id}", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mockMvc.perform(post("/simulator/charges/{id}/fail", charge.publicId()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void failActionTransitionsPendingCharge() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("1.0000"));
    mockMvc.perform(post("/simulator/charges/{id}/fail", charge.publicId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void unknownChargeReturns404() throws Exception {
    mockMvc.perform(get("/simulator/charges/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void networkPortEchoesAmountOnGetCharge() throws Exception {
    var charge = paymentNetwork.createCharge(Money.ofBrl("42.0000"));
    var fetched = paymentNetwork.getCharge(charge.publicId());
    org.junit.jupiter.api.Assertions.assertEquals(0, fetched.amount().compareTo(Money.ofBrl("42.0000")));
    org.junit.jupiter.api.Assertions.assertEquals(
        com.leandrossb.nummus.payments.application.ChargeStatus.PENDING, fetched.status());
  }
}
```

- [ ] **Step 8: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest='SimulatorSchemaTest,SimulatorRestApiTest'
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (1 + 4); full suite 94/94.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/psp_simulator/ src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java src/main/resources/db/migration/V6__psp_simulator_schema.sql src/test/java/com/leandrossb/nummus/psp_simulator/
git commit -m "feat: add the PSP simulator module

DB-backed simulated network with payer-facing REST, status-guarded
terminal transitions, and the PaymentNetwork adapter the payments
module will consume.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Payments repository (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/PaymentsRepositoryTest.java`

**Interfaces:**
- Consumes: Task 2 domain, V5 schema.
- Produces: `interface PaymentsRepository { PaymentIntent insert(PaymentIntent); Optional<PaymentIntent> findByPublicId(UUID); boolean transitionToExpired(UUID publicId); boolean transitionToFailed(UUID publicId); boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt); }` — the last three are status-guarded (`WHERE status = 'CREATED'`).

- [ ] **Step 1: Write the failing integration test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentsRepositoryTest extends IntegrationTestBase {

  @Autowired
  private PaymentsRepository repository;

  private PaymentIntent newIntent() {
    return new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), Money.ofBrl("10.0000"),
        IntentStatus.CREATED, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30)),
        Instant.now(), null, null);
  }

  @Test
  void insertAndFindByPublicIdRoundTrip() {
    var intent = newIntent();
    repository.insert(intent);

    var found = repository.findByPublicId(intent.publicId()).orElseThrow();
    assertEquals(intent.publicId(), found.publicId());
    assertEquals(intent.accountPublicId(), found.accountPublicId());
    assertEquals(0, found.amount().compareTo(Money.ofBrl("10.0000")));
    assertEquals(IntentStatus.CREATED, found.status());
    assertEquals(intent.chargePublicId(), found.chargePublicId());
    assertTrue(found.settledAt() == null);
    assertTrue(found.journalTransactionPublicId() == null);
  }

  @Test
  void findByPublicIdReturnsEmptyForUnknownId() {
    assertTrue(repository.findByPublicId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void guardedTransitionsApplyOnceThenRefuse() {
    var intent = newIntent();
    repository.insert(intent);

    assertTrue(repository.transitionToExpired(intent.publicId()));
    assertEquals(IntentStatus.EXPIRED, repository.findByPublicId(intent.publicId()).orElseThrow().status());
    assertFalse(repository.transitionToExpired(intent.publicId()));

    var other = newIntent();
    repository.insert(other);
    var journalTx = UUID.randomUUID();
    assertTrue(repository.markSettled(other.publicId(), journalTx, Instant.now()));
    var settled = repository.findByPublicId(other.publicId()).orElseThrow();
    assertEquals(IntentStatus.SETTLED, settled.status());
    assertEquals(journalTx, settled.journalTransactionPublicId());
    assertTrue(settled.settledAt() != null);
    assertFalse(repository.markSettled(other.publicId(), UUID.randomUUID(), Instant.now()));

    var third = newIntent();
    repository.insert(third);
    assertTrue(repository.transitionToFailed(third.publicId()));
    assertFalse(repository.transitionToFailed(third.publicId()));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsRepositoryTest
```

Expected: FAIL — no bean implements `PaymentsRepository`.

- [ ] **Step 3: Implement**

`PaymentsRepository.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module. Every state change is status-guarded. */
public interface PaymentsRepository {

  PaymentIntent insert(PaymentIntent intent);

  Optional<PaymentIntent> findByPublicId(UUID publicId);

  /** CREATED → EXPIRED. @return false when the intent is not CREATED. */
  boolean transitionToExpired(UUID publicId);

  /** CREATED → FAILED. @return false when the intent is not CREATED. */
  boolean transitionToFailed(UUID publicId);

  /** CREATED → SETTLED with the exactly-once journal link. @return false when not CREATED. */
  boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt);
}
```

`JdbcClientPaymentsRepository.java`:

```java
package com.leandrossb.nummus.payments.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientPaymentsRepository implements PaymentsRepository {

  private final JdbcClient jdbc;

  public JdbcClientPaymentsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public PaymentIntent insert(PaymentIntent intent) {
    jdbc.sql("""
        insert into payments.payment_intent
          (public_id, account_public_id, amount, status, charge_public_id, expires_at, created_at)
        values (:publicId, :accountPublicId, :amount, :status, :chargePublicId, :expiresAt, :createdAt)
        """)
        .param("publicId", intent.publicId())
        .param("accountPublicId", intent.accountPublicId())
        .param("amount", intent.amount().amount())
        .param("status", intent.status().name())
        .param("chargePublicId", intent.chargePublicId())
        .param("expiresAt", toOffsetDateTime(intent.expiresAt()))
        .param("createdAt", toOffsetDateTime(intent.createdAt()))
        .update();
    return intent;
  }

  @Override
  public Optional<PaymentIntent> findByPublicId(UUID publicId) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, status, charge_public_id,
               expires_at, created_at, settled_at, journal_transaction_public_id
        from payments.payment_intent where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapIntent(rs))
        .optional();
  }

  @Override
  public boolean transitionToExpired(UUID publicId) {
    return guardedTransition(publicId, "EXPIRED", null, null);
  }

  @Override
  public boolean transitionToFailed(UUID publicId) {
    return guardedTransition(publicId, "FAILED", null, null);
  }

  @Override
  public boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt) {
    int updated = jdbc.sql("""
        update payments.payment_intent
        set status = 'SETTLED', settled_at = :settledAt, journal_transaction_public_id = :journalTx
        where public_id = :publicId and status = 'CREATED'
        """)
        .param("settledAt", toOffsetDateTime(settledAt))
        .param("journalTx", journalTransactionPublicId)
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private boolean guardedTransition(UUID publicId, String target, UUID journalTx, Instant at) {
    int updated = jdbc.sql("""
        update payments.payment_intent set status = :status
        where public_id = :publicId and status = 'CREATED'
        """)
        .param("status", target)
        .param("publicId", publicId)
        .update();
    return updated == 1;
  }

  private PaymentIntent mapIntent(ResultSet rs) throws SQLException {
    OffsetDateTime settledAt = rs.getObject("settled_at", OffsetDateTime.class);
    return new PaymentIntent(
        rs.getObject("public_id", UUID.class),
        rs.getObject("account_public_id", UUID.class),
        Money.of(rs.getBigDecimal("amount"), Currency.getInstance("BRL")),
        IntentStatus.valueOf(rs.getString("status")),
        rs.getObject("charge_public_id", UUID.class),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        settledAt == null ? null : settledAt.toInstant(),
        rs.getObject("journal_transaction_public_id", UUID.class));
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
```

(Remove the unused `Duration` import if your final code doesn't need it — the shown file doesn't.)

- [ ] **Step 4: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsRepositoryTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (3); full suite 97/97.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/ src/test/java/com/leandrossb/nummus/payments/PaymentsRepositoryTest.java
git commit -m "feat: add payments repository with guarded transitions

Insert, lookup, and single-row conditional state changes with
rowcount verification — the exactly-once settlement substrate.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: `PaymentsServiceImpl` — lazy expiry and exactly-once settle (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/application/InMemoryPaymentsRepository.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/application/FakePaymentNetwork.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/application/PaymentsServiceImplTest.java`

**Interfaces:**
- Consumes: Task 2-4 products; M2 `AccountsService` (`get(UUID): PaymentAccount` with `ledgerAccountPublicId()` + `status()`), M1 `Ledger.post/balance`.
- Produces (used by Task 6): `interface PaymentsService { PaymentIntent create(CreateIntentCommand cmd); PaymentIntent get(UUID publicId); }` — `get` applies lazy expiry then lazy settle. `@Service class PaymentsServiceImpl implements PaymentsService`, ctor `(Ledger, AccountsService, PaymentNetwork, PaymentsRepository)`.

- [ ] **Step 1: Write the fakes**

`InMemoryPaymentsRepository.java` (mirrors the guarded semantics in memory):

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake mirroring the repository's status-guarded transitions. */
public class InMemoryPaymentsRepository implements PaymentsRepository {

  private final Map<UUID, PaymentIntent> intents = new ConcurrentHashMap<>();

  @Override
  public PaymentIntent insert(PaymentIntent intent) {
    intents.put(intent.publicId(), intent);
    return intent;
  }

  @Override
  public Optional<PaymentIntent> findByPublicId(UUID publicId) {
    return Optional.ofNullable(intents.get(publicId));
  }

  @Override
  public boolean transitionToExpired(UUID publicId) {
    return guarded(publicId, IntentStatus.EXPIRED, null, null);
  }

  @Override
  public boolean transitionToFailed(UUID publicId) {
    return guarded(publicId, IntentStatus.FAILED, null, null);
  }

  @Override
  public boolean markSettled(UUID publicId, UUID journalTransactionPublicId, Instant settledAt) {
    return guarded(publicId, IntentStatus.SETTLED, journalTransactionPublicId, settledAt);
  }

  private synchronized boolean guarded(UUID publicId, IntentStatus target, UUID journalTx, Instant at) {
    var current = intents.get(publicId);
    if (current == null || current.status() != IntentStatus.CREATED) {
      return false;
    }
    intents.put(publicId, new PaymentIntent(current.publicId(), current.accountPublicId(),
        current.amount(), target, current.chargePublicId(), current.expiresAt(),
        current.createdAt(), at, journalTx));
    return true;
  }

  /** Test driver: move an intent's expiry into the past. */
  public void agePastExpiry(UUID publicId) {
    intents.computeIfPresent(publicId, (id, intent) -> new PaymentIntent(
        intent.publicId(), intent.accountPublicId(), intent.amount(), intent.status(),
        intent.chargePublicId(), Instant.now().minusSeconds(1),
        intent.createdAt(), intent.settledAt(), intent.journalTransactionPublicId()));
  }
}
```

`FakePaymentNetwork.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic in-memory network for unit tests: charges stay PENDING until driven. */
public class FakePaymentNetwork implements PaymentNetwork {

  private final Map<UUID, NetworkCharge> charges = new ConcurrentHashMap<>();

  @Override
  public NetworkCharge createCharge(Money amount) {
    var charge = new NetworkCharge(UUID.randomUUID(), amount, ChargeStatus.PENDING);
    charges.put(charge.publicId(), charge);
    return charge;
  }

  @Override
  public NetworkCharge getCharge(UUID chargePublicId) {
    return charges.get(chargePublicId);
  }

  /** Test driver: the payer pays. */
  public void succeed(UUID chargePublicId) {
    transition(chargePublicId, ChargeStatus.SUCCEEDED);
  }

  /** Test driver: the payer fails. */
  public void fail(UUID chargePublicId) {
    transition(chargePublicId, ChargeStatus.FAILED);
  }

  /** Test driver: mutate the charge amount — the invariant breach the service must catch. */
  public void mutateAmount(UUID chargePublicId, Money amount) {
    charges.computeIfPresent(chargePublicId,
        (id, charge) -> new NetworkCharge(id, amount, charge.status()));
  }

  private void transition(UUID chargePublicId, ChargeStatus target) {
    charges.computeIfPresent(chargePublicId,
        (id, charge) -> new NetworkCharge(id, charge.amount(), target));
  }
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.leandrossb.nummus.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.AccountsServiceImpl;
import com.leandrossb.nummus.accounts.application.InMemoryAccountsRepository;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.InMemoryLedgerRepository;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.LedgerServiceImpl;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentsServiceImplTest {

  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository());
  private final FakePaymentNetwork network = new FakePaymentNetwork();
  private final InMemoryPaymentsRepository repo = new InMemoryPaymentsRepository();
  private final PaymentsService payments = new PaymentsServiceImpl(ledger, accounts, network, repo);

  @Test
  void createOpensChargeAndStoresCreatedIntent() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    assertNotNull(intent.publicId());
    assertEquals(IntentStatus.CREATED, intent.status());
    assertNotNull(intent.chargePublicId());
    assertNotNull(intent.expiresAt());
    assertEquals(ChargeStatus.PENDING, network.getCharge(intent.chargePublicId()).status());
  }

  @Test
  void createValidatesTtlBoundsAndAccount() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    assertThrows(IllegalArgumentException.class, () -> payments.create(
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), Duration.ofSeconds(59))));
    assertThrows(IllegalArgumentException.class, () -> payments.create(
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), Duration.ofSeconds(86401))));
    assertThrows(com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException.class, () ->
        payments.create(new CreateIntentCommand(UUID.randomUUID(), Money.ofBrl("1.0000"), null)));
    assertThrows(NullPointerException.class, () -> payments.create(null));
  }

  @Test
  void frozenAccountRejectsCreation() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    accounts.freeze(account.publicId());
    assertThrows(com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException.class, () ->
        payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), null)));
  }

  @Test
  void succeededChargeSettlesExactlyOnceWithBalancedEntry() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    network.succeed(intent.chargePublicId());

    var settled = payments.get(intent.publicId());
    assertEquals(IntentStatus.SETTLED, settled.status());
    assertNotNull(settled.journalTransactionPublicId());
    assertNotNull(settled.settledAt());

    // clearing debited, merchant payable credited (raw DR-CR view of the ledger)
    assertEquals(0, ledger.balance(PaymentClearingAccount.PUBLIC_ID)
        .compareTo(Money.ofBrl("10.0000")));
    var again = payments.get(intent.publicId());
    assertEquals(IntentStatus.SETTLED, again.status());
    assertEquals(settled.journalTransactionPublicId(), again.journalTransactionPublicId());
  }

  @Test
  void failedChargeFailsTheIntent() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.fail(intent.chargePublicId());
    assertEquals(IntentStatus.FAILED, payments.get(intent.publicId()).status());
  }

  @Test
  void expiredIntentRefusesSettlementEvenAfterSuccess() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    repo.agePastExpiry(intent.publicId());
    network.succeed(intent.chargePublicId());
    assertEquals(IntentStatus.EXPIRED, payments.get(intent.publicId()).status());
  }

  @Test
  void amountMismatchIsAnInvariantBreach() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.succeed(intent.chargePublicId());
    network.mutateAmount(intent.chargePublicId(), Money.ofBrl("6.0000"));
    assertThrows(ChargeAmountMismatchException.class, () -> payments.get(intent.publicId()));
  }

  @Test
  void frozenAtSettleTimeLeavesIntentCreatedAndSettlesAfterUnfreeze() {
    var account = accounts.open(new OpenAccountCommand("merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.succeed(intent.chargePublicId());
    accounts.freeze(account.publicId());

    assertThrows(com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException.class,
        () -> payments.get(intent.publicId()));
    assertEquals(IntentStatus.CREATED, repo.findByPublicId(intent.publicId()).orElseThrow().status());

    accounts.unfreeze(account.publicId());
    assertEquals(IntentStatus.SETTLED, payments.get(intent.publicId()).status());
  }

  @Test
  void unknownIntentThrows() {
    assertThrows(UnknownPaymentIntentException.class, () -> payments.get(UUID.randomUUID()));
  }
}
```

- [ ] **Step 3: Run to verify compilation fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsServiceImplTest
```

Expected: COMPILATION ERROR — `PaymentsService`/`PaymentsServiceImpl` do not exist.

- [ ] **Step 4: Implement**

`PaymentsService.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.util.UUID;

/**
 * The payments module's internal API. Reads apply lazy expiry then lazy settlement;
 * settlement is exactly-once and posts the balanced clearing/payable entry.
 */
public interface PaymentsService {

  PaymentIntent create(CreateIntentCommand cmd);

  /** Applies lazy expiry and lazy settlement, then returns the current state. */
  PaymentIntent get(UUID publicId);
}
```

`PaymentsServiceImpl.java`:

```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.ConcurrentSettlementException;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentsServiceImpl implements PaymentsService {

  static final Duration DEFAULT_TTL = Duration.ofSeconds(1800);
  private static final Duration MIN_TTL = Duration.ofSeconds(60);
  private static final Duration MAX_TTL = Duration.ofSeconds(86400);

  private final Ledger ledger;
  private final AccountsService accounts;
  private final PaymentNetwork network;
  private final PaymentsRepository repository;

  public PaymentsServiceImpl(Ledger ledger, AccountsService accounts, PaymentNetwork network,
      PaymentsRepository repository) {
    this.ledger = ledger;
    this.accounts = accounts;
    this.network = network;
    this.repository = repository;
  }

  @Override
  @Transactional
  public PaymentIntent create(CreateIntentCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    Duration ttl = cmd.ttl() == null ? DEFAULT_TTL : cmd.ttl();
    if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException(
          "ttl must be between 60 and 86400 seconds: " + ttl.toSeconds());
    }
    var account = accounts.get(cmd.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    var charge = network.createCharge(cmd.amount());
    return repository.insert(new PaymentIntent(UUID.randomUUID(), account.publicId(),
        cmd.amount(), IntentStatus.CREATED, charge.publicId(), Instant.now().plus(ttl),
        Instant.now(), null, null));
  }

  @Override
  @Transactional
  public PaymentIntent get(UUID publicId) {
    var intent = repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPaymentIntentException(publicId));
    if (intent.status() != IntentStatus.CREATED) {
      return intent;
    }
    if (Instant.now().isAfter(intent.expiresAt())) {
      repository.transitionToExpired(publicId);
      return repository.findByPublicId(publicId).orElseThrow();
    }
    var charge = network.getCharge(intent.chargePublicId());
    if (charge.amount().compareTo(intent.amount()) != 0) {
      throw new ChargeAmountMismatchException(charge.publicId(), intent.amount(), charge.amount());
    }
    return switch (charge.status()) {
      case PENDING -> intent;
      case FAILED -> {
        repository.transitionToFailed(publicId);
        yield repository.findByPublicId(publicId).orElseThrow();
      }
      case SUCCEEDED -> settle(intent);
    };
  }

  private PaymentIntent settle(PaymentIntent intent) {
    var account = accounts.get(intent.accountPublicId());
    if (account.status() != AccountStatus.ACTIVE) {
      throw new PaymentAccountNotActiveException(account.publicId(), account.status());
    }
    var posted = ledger.post(new PostTransactionCommand(
        "settlement " + intent.publicId(), List.of(
            new PostingDraft(PaymentClearingAccount.PUBLIC_ID, Direction.DEBIT, intent.amount()),
            new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, intent.amount()))));
    if (!repository.markSettled(intent.publicId(), posted.publicId(), Instant.now())) {
      // A racing settler won the guarded transition; roll this posting back with the
      // transaction and let the caller re-read the SETTLED state.
      throw new ConcurrentSettlementException(intent.publicId());
    }
    return repository.findByPublicId(intent.publicId()).orElseThrow();
  }
}
```

- [ ] **Step 5: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsServiceImplTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (9); full suite 106/106.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/application/ src/test/java/com/leandrossb/nummus/payments/application/
git commit -m "feat: add payments service with lazy expiry and settlement

Reads expire lazily, poll the network, and settle exactly once:
the balanced clearing/payable entry posts first, the status-guarded
SETTLED transition commits it, and a lost race rolls the posting back.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: Payments REST and error mapping (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreateIntentRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/interfaces/dto/IntentResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/PaymentsRestApiTest.java`

**Interfaces:**
- Consumes: `PaymentsService` (Task 5), shared advice (Task 1).
- Produces: `POST /v1/payment-intents` (201 + Location + IntentResponse), `GET /v1/payment-intents/{id}` (200, lazy state applied). Advice gains: `UnknownPaymentIntentException` → 404; `ConcurrentSettlementException`, `IntentStatus`-based conflicts (none exist — see mapping note) → 409; `ChargeAmountMismatchException` → 500 (explicit).

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PaymentsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  private String createAccount() {
    return accountsService.open(new OpenAccountCommand("Rest Merchant"))
        .publicId().toString();
  }

  private String createIntent(String accountId, String amountJson) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amountJson + "}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturns201WithIntentBody() throws Exception {
    String accountId = createAccount();
    mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":10.0000}"))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.publicId").exists())
        .andExpect(jsonPath("$.accountId").value(accountId))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.chargeId").exists())
        .andExpect(jsonPath("$.settledAt").doesNotExist());
  }

  @Test
  void payerPaymentThenGetSettlesAndCreditsMerchantBalance() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "10.0000");

    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"))
        .andExpect(jsonPath("$.settledAt").exists());

    mockMvc.perform(get("/v1/accounts/{id}/balance", UUID.fromString(accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(10.0000))
        .andExpect(jsonPath("$.currency").value("BRL"));
  }

  @Test
  void expiredIntentRefusesSettlement() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");

    // age the intent past its expiry through the database (60s minimum ttl)
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET expires_at = now() - interval '1 second'");
    }
    simulator.pay(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"));
  }

  @Test
  void failedChargeFailsTheIntentOverRest() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.fail(UUID.fromString(chargeId));

    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));
  }

  @Test
  void frozenAccountSettleConflictThenUnfreezeSettles() throws Exception {
    String accountId = createAccount();
    String location = createIntent(accountId, "5.0000");
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString(), "$.chargeId");
    simulator.pay(UUID.fromString(chargeId));
    accountsService.freeze(UUID.fromString(accountId));

    mockMvc.perform(get(location))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").exists());

    mockMvc.perform(get(location))
        .andExpect(status().isConflict());

    accountsService.unfreeze(UUID.fromString(accountId));
    mockMvc.perform(get(location))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SETTLED"));
    mockMvc.perform(get("/v1/accounts/{id}/balance", UUID.fromString(accountId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(5.0000));
  }

  @Test
  void validationFailuresReturn400() throws Exception {
    String accountId = createAccount();
    mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":0.0000}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":1.12345}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":5.0000}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAccountAndIntentReturn404() throws Exception {
    mockMvc.perform(post("/v1/payment-intents")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":5.0000}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/payment-intents/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
```

(The `adminConnection()` updater ages ALL intents — fine in a fresh-container-per-JVM run; the test's own intent is the only one in flight.)

- [ ] **Step 2: Run to verify it fails**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsRestApiTest
```

Expected: tests COMPILE and FAIL with 404s — no `/v1/payment-intents` mapping exists.

- [ ] **Step 3: Implement DTOs and controller**

`CreateIntentRequest.java`:

```java
package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for creating a payment intent. Scale is validated by Money (400 on breach). */
public record CreateIntentRequest(
    @NotNull(message = "accountId must not be null") UUID accountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive") BigDecimal amount,
    @Min(value = 60, message = "expiresInSeconds must be at least 60")
    @Max(value = 86400, message = "expiresInSeconds must be at most 86400") Long expiresInSeconds) {
}
```

`IntentResponse.java`:

```java
package com.leandrossb.nummus.payments.interfaces.dto;

import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REST view of a payment intent. UUIDs only; settledAt/journal link appear post-settlement. */
public record IntentResponse(
    UUID publicId,
    UUID accountId,
    BigDecimal amount,
    String currency,
    String status,
    UUID chargeId,
    Instant expiresAt,
    Instant createdAt,
    Instant settledAt) {

  public static IntentResponse from(PaymentIntent intent) {
    return new IntentResponse(intent.publicId(), intent.accountPublicId(),
        intent.amount().amount(), intent.amount().currency().getCurrencyCode(),
        intent.status().name(), intent.chargePublicId(), intent.expiresAt(),
        intent.createdAt(), intent.settledAt());
  }
}
```

`PaymentsController.java`:

```java
package com.leandrossb.nummus.payments.interfaces;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.interfaces.dto.CreateIntentRequest;
import com.leandrossb.nummus.payments.interfaces.dto.IntentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment-intents")
class PaymentsController {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final PaymentsService payments;

  PaymentsController(PaymentsService payments) {
    this.payments = payments;
  }

  @PostMapping
  ResponseEntity<IntentResponse> create(@Valid @RequestBody CreateIntentRequest request) {
    var intent = payments.create(new CreateIntentCommand(request.accountId(),
        Money.of(request.amount(), BRL),
        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds())));
    return ResponseEntity
        .created(URI.create("/v1/payment-intents/" + intent.publicId()))
        .body(IntentResponse.from(intent));
  }

  @GetMapping("/{id}")
  IntentResponse get(@PathVariable UUID id) {
    return IntentResponse.from(payments.get(id));
  }
}
```

- [ ] **Step 4: Extend the shared advice**

In `com.leandrossb.nummus.interfaces.GlobalExceptionHandler`: import `com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException`, `ConcurrentSettlementException`, `UnknownPaymentIntentException`. Add `UnknownPaymentIntentException` to the `notFound` list, `ConcurrentSettlementException` to the `conflict` list, and one new method:

```java
  @ExceptionHandler(ChargeAmountMismatchException.class)
  ProblemDetail invariantBreach(ChargeAmountMismatchException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
  }
```

- [ ] **Step 5: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest=PaymentsRestApiTest
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (7); full suite 113/113.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/interfaces/ src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java src/test/java/com/leandrossb/nummus/payments/PaymentsRestApiTest.java
git commit -m "feat: add payment intents REST endpoints

Create and lazily-settling reads over /v1/payment-intents, with
payments vocabulary in the shared problem+json error model.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Boundary rules for the new modules + settlement concurrency proof

**Files:**
- Modify: `src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java`
- Create: `src/test/java/com/leandrossb/nummus/payments/PaymentSettlementConcurrencyTest.java`

**Interfaces:**
- Consumes: both modules now exist (rules get non-empty selections).
- Produces: extended boundary rules; the concurrency proof (racing settlers → exactly one journal entry).

- [ ] **Step 1: Extend `ModuleBoundaryTest` with four rules**

Add imports (none new needed beyond existing) and these rules inside the class:

```java
  @ArchTest
  static final ArchRule paymentsNeverTouchForeignInfrastructure =
      noClasses().that().resideInAPackage("..payments..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..ledger.infrastructure..", "..accounts.infrastructure..",
              "..psp_simulator..");

  @ArchTest
  static final ArchRule simulatorTouchesOnlyTheNetworkPort =
      noClasses().that().resideInAPackage("..psp_simulator..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..accounts..");

  @ArchTest
  static final ArchRule foreignModulesNeverTouchPayments =
      noClasses().that().resideInAnyPackage("..ledger..", "..accounts..")
          .should().dependOnClassesThat().resideInAPackage("..payments..");

  @ArchTest
  static final ArchRule newDomainPackagesStayFrameworkFree =
      noClasses().that().resideInAnyPackage("..payments.domain..", "..psp_simulator.domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("org.springframework..", "java.sql..", "jakarta.persistence..");
```

(Extends the M2 rule set; the existing four rules stay untouched. The simulator's rule bans everything in payments except `payments.application` — the port it implements — plus `..accounts..`; `ledger.domain` vocabulary such as `Money` remains allowed to the simulator, mirroring how the accounts module consumes the ledger.)

- [ ] **Step 2: Write the concurrency proof**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentSettlementConcurrencyTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  @Test
  @Timeout(120)
  void racingSettlersPostExactlyOneJournalEntry() throws Exception {
    var account = accountsService.open(new OpenAccountCommand("Race Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("20.0000"), null));
    simulator.pay(intent.chargePublicId());

    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          try {
            payments.get(intent.publicId());
            return Boolean.TRUE;
          } catch (RuntimeException e) {
            return Boolean.FALSE; // ConcurrentSettlementException losers are expected
          }
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(90, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly-once proof: the intent is settled and the merchant was credited
    // exactly the intent amount — a second settlement entry would double it.
    assertEquals(IntentStatus.SETTLED, payments.get(intent.publicId()).status());
    assertEquals(0, accountsService.balance(account.publicId()).compareTo(Money.ofBrl("20.0000")));
  }
}
```

- [ ] **Step 3: Run focused + full suite**

```bash
<ENV_PREFIX> ./mvnw test -Dtest='ModuleBoundaryTest,PaymentSettlementConcurrencyTest'
<ENV_PREFIX> ./mvnw test
```

Expected: focused PASS (8 rules + 1); full suite 118/118.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/architecture/ src/test/java/com/leandrossb/nummus/payments/PaymentSettlementConcurrencyTest.java
git commit -m "test: guard the new module boundaries and prove exactly-once settlement

Four ArchUnit rules for payments and psp-simulator, and a concurrent
settle race that posts exactly one journal entry.

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` (M3 checkbox)
- Modify: `docs/m2-backlog.md` (strike the M3-adjacent items)

**Interfaces:**
- Consumes: everything.
- Produces: the M3 success-criteria evidence.

- [ ] **Step 1: Run the full build**

```bash
<ENV_PREFIX> ./mvnw verify
```

Expected: `BUILD SUCCESS`, all tests green (118 expected).

- [ ] **Step 2: Update `README.md`**

```markdown
- [x] M3 — Payment intents with the PSP simulator
```

- [ ] **Step 3: Update `docs/m2-backlog.md`**

Strike the now-implemented items from "From the M2 review": the TOCTOU status-guarded-transition bullet (implemented in Tasks 4-5), the end-to-end commit-time trigger → 409 bullet (implemented in Task 6's frozen-settle test). Keep: roles-test `closed_at` probe + SQLSTATE (still open), handler scoping (resolved by Task 1's relocation — strike it too), and the currency/500-body note (currency done; 500-body note stays). Rewrite the section header to "## From the M2 review (resolved in M3)" listing what remains honestly.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/m2-backlog.md
git commit -m "docs: mark M3 payment intents complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 5: Report**

Report the final `./mvnw verify` summary line and test count verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| Shared error advice relocation | 1 |
| payments domain + PaymentNetwork port + V5 (clearing seed) | 2 |
| psp-simulator module (V6, service, store, adapter, REST) | 3 |
| Payments repository with guarded transitions | 4 |
| Lazy expiry + exactly-once settle + fail-fast | 5 |
| REST endpoints + error vocabulary | 6 |
| ArchUnit rules for new modules + concurrency proof | 7 |
| Success criteria, README, backlog | 8 |
