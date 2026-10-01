# M24 Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A separate-port Actuator surface — liveness/readiness probes and a Prometheus scrape endpoint — plus `nummus.*` lifecycle counters incremented at the payment state machines' won transitions and the webhook delivery worker.

**Architecture:** `management.server.port: 9090` isolates management traffic from the API's auth/rate-limit filters by construction. Counters are registry-cached one-liners beside the guarded transitions that already publish lifecycle events; tags carry outcomes only (never merchant/account ids).

**Tech Stack:** Spring Boot Actuator, Micrometer (`micrometer-registry-prometheus`), JUnit 5, Testcontainers (PostgreSQL).

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits (`test:`, `feat:`, `docs:`, `fix:`).
- Actuator is exposed ONLY on the management port (`health` and `prometheus` endpoints); the main API port must 404 `/actuator/health`.
- Metric tags carry outcomes/states only — never merchant, account, or any resource ids (cardinality).
- Counters increment only on WON transitions (the same guard the lifecycle-event publish uses — a racing loser must not double-count).
- Money stays ledger-derived; metrics are observability, never accounting.
- No DB migration (`V24` stays the head).
- **Constructor ripple rule:** adding `MeterRegistry` to service constructors breaks every direct `new <Impl>(...)` in tests — update each to pass `new SimpleMeterRegistry()` (import `io.micrometer.core.instrument.simple.SimpleMeterRegistry`) in the same commit.
- **Verification protocol (no local Maven):** implementer subagents edit, run no build, and commit (test commit first, then implementation). The controller runs all Maven commands remotely on megalan after each task:
  `ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B <branch> origin/<branch> && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<TestClass>'`
  Branches are pushed only after a task's green commit.

---

### Task 1: Actuator on the management port

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/leandrossb/nummus/observability/ObservabilityTestBase.java`
- Test: `src/test/java/com/leandrossb/nummus/observability/ManagementPortRestApiTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` on the management port; Task 2's test reuses this class's scrape helper pattern.

- [ ] **Step 1: Write the failing test**

First a dedicated base — the shared `IntegrationTestBase` boots `WebEnvironment.MOCK` (no real ports), so the management surface needs its own `RANDOM_PORT` context with its own container wiring:

`src/test/java/com/leandrossb/nummus/observability/ObservabilityTestBase.java`:

```java
package com.leandrossb.nummus.observability;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots a real server pair (API + management) for the observability surface:
 *  the shared MockMvc base has no ports to probe. Own container — the context
 *  differs, so sharing IntegrationTestBase's cached one is not possible. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ObservabilityTestBase {

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18-alpine")
          .withCommand("postgres", "-c", "max_connections=300")
          .withStartupTimeout(Duration.ofMinutes(3));

  static {
    POSTGRES.start();
  }

  protected final TestRestTemplate rest = new TestRestTemplate();

  @DynamicPropertySource
  static void registerDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }
}
```

(Mirror `IntegrationTestBase`'s exact property names and container settings — read it first and copy its wiring verbatim, including anything this sketch missed.)

`src/test/java/com/leandrossb/nummus/observability/ManagementPortRestApiTest.java`:

```java
package com.leandrossb.nummus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Management traffic lives on its own port: probes and the Prometheus
 *  scrape answer there, and the merchant API port never serves actuator. */
@TestPropertySource(properties = "management.server.port=0")
class ManagementPortRestApiTest extends ObservabilityTestBase {

  @Value("${local.management.port}")
  private int managementPort;

  @Value("${local.server.port}")
  private int apiPort;

  @Test
  void livenessAndReadinessAnswerUpOnTheManagementPort() {
    ResponseEntity<String> liveness =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/liveness",
            String.class);
    assertEquals(HttpStatus.OK, liveness.getStatusCode());
    assertTrue(liveness.getBody().contains("UP"), liveness.getBody());
    ResponseEntity<String> readiness =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/readiness",
            String.class);
    assertEquals(HttpStatus.OK, readiness.getStatusCode());
    assertTrue(readiness.getBody().contains("UP"), readiness.getBody());
  }

  @Test
  void prometheusScrapeAnswersOnTheManagementPort() {
    ResponseEntity<String> scrape =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus",
            String.class);
    assertEquals(HttpStatus.OK, scrape.getStatusCode());
    assertTrue(scrape.getBody().contains("# TYPE"));
  }

  @Test
  void mainApiPortNeverServesActuator() {
    assertNotEquals(apiPort, managementPort);
    ResponseEntity<String> leaked =
        rest.getForEntity("http://127.0.0.1:" + apiPort + "/actuator/health", String.class);
    assertEquals(HttpStatus.NOT_FOUND, leaked.getStatusCode());
  }
}
```

(The `@Value("${local.management.port}")` / `${local.server.port}` properties are populated for `RANDOM_PORT` tests; if the property names differ in this Boot version, an existing `@LocalManagementPort`-style annotation is the alternative — resolve by reading Spring Boot's test support for the version in `pom.xml`.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/observability/
git commit -m "test: cover the management port probes and scrape"
```

- [ ] **Step 3: Implement**

`pom.xml` — inside `<dependencies>`, mirroring the existing starter entries' style:

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

(No versions — the Boot parent/BOM manages both.)

`src/main/resources/application.yml` — append:

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 4: Commit the implementation**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "feat: expose actuator health probes and prometheus on a management port"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=ManagementPortRestApiTest` on megalan. Expected: 3 tests PASS.

---

### Task 2: Lifecycle counters

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookDeliveryWorker.java`
- Modify: every test constructing the four classes directly (constructor ripple rule)
- Test: `src/test/java/com/leandrossb/nummus/observability/LifecycleMetricsTest.java`

**Interfaces:**
- Consumes: Task 1's management-port surface.
- Produces: counters `nummus_intents_total{outcome=created|settled|failed|expired}`, `nummus_payouts_total{outcome=requested|executed|failed|expired}`, `nummus_refunds_total{outcome=requested|settled}`, `nummus_webhook_deliveries_total{outcome=attempted|succeeded|failed}`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** The state machines' throughput lands in the scrape: drive one lifecycle
 *  and read the counters back from Prometheus's exposition. */
@TestPropertySource(properties = "management.server.port=0")
class LifecycleMetricsTest extends ObservabilityTestBase {

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  @Value("${local.management.port}")
  private int managementPort;

  @Value("${local.server.port}")
  private int apiPort;

  private UUID merchantId;

  @BeforeEach
  void createFixtures() {
    String operatorAuth = "Bearer " + operatorKeys.create("metrics-probe", null, null).secret();
    var headers = new org.springframework.http.HttpHeaders();
    headers.set("Authorization", operatorAuth);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    var response = rest.postForEntity(
        "http://127.0.0.1:" + apiPort + "/v1/merchants",
        new org.springframework.http.HttpEntity<>("{\"name\":\"Lifecycle Metrics Merchant\"}",
            headers),
        String.class);
    assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
    merchantId = UUID.fromString(
        com.jayway.jsonpath.JsonPath.read(response.getBody(), "$.merchantId"));
  }

  private String scrape() {
    ResponseEntity<String> response =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus",
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  @Test
  void intentLifecycleIncrementsTheCounters() {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Metrics Account"));
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());

    String body = scrape();
    assertTrue(body.contains("nummus_intents_total{outcome=\"created\"}"),
        "created counter missing from scrape");
    assertTrue(body.contains("nummus_intents_total{outcome=\"settled\"}"),
        "settled counter missing from scrape");
  }
}
```

(Follow Task 1's resolution for the `TestRestTemplate`/port annotations.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/observability/LifecycleMetricsTest.java
git commit -m "test: cover the lifecycle counters in the prometheus scrape"
```

- [ ] **Step 3: Implement the counters**

Pattern, one per site — a private helper per class keeps call sites one-liners:

```java
  private void count(String name, String outcome) {
    registry.counter(name, "outcome", outcome).increment();
  }
```

with `private final MeterRegistry registry;` added to the constructor (import `io.micrometer.core.instrument.MeterRegistry`). Placement — increment ONLY where the existing code already publishes/wins the transition (read each method; the guards are `if (repository.transitionToX(...))` / `if (repository.markSettled(...))`, and `create` methods increment just before/after their `repository.insert`):

- `PaymentsServiceImpl`: `create` → `count("nummus.intents", "created")`; the `transitionToExpired` win → `"expired"`; the `transitionToFailed` win → `"failed"`; `settle` after the `markSettled` win → `"settled"`.
- `PayoutsServiceImpl`: `create` → `count("nummus.payouts", "requested")`; `execute` after the `markSettled` win → `"executed"`; the failure and expiry resolution wins (the same branches that publish `PayoutLifecycleEvents` failure/expiry) → `"failed"` / `"expired"`.
- `RefundsServiceImpl`: `create` → `count("nummus.refunds", "requested")`; `execute` after the `markSettled` win → `"settled"`.
- `WebhookDeliveryWorker`: each HTTP attempt → `count("nummus.webhook_deliveries", "attempted")`; the terminal success path → `"succeeded"`; the terminal failure path (attempts exhausted or permanently failed) → `"failed"`.

Counter names use dots at the call site (`nummus.intents`) — Micrometer's Prometheus registry renders them as `nummus_intents_total` with the `outcome` tag.

Constructor ripple rule: update every direct `new PaymentsServiceImpl(...)`, `new PayoutsServiceImpl(...)`, `new RefundsServiceImpl(...)`, `new WebhookDeliveryWorker(...)` in `src/test/java` to pass `new SimpleMeterRegistry()` as the new last parameter (grep for each constructor name; some classes may have test subclasses or builders — mirror the existing pattern).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: count payment and webhook lifecycle transitions"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='LifecycleMetricsTest,ManagementPortRestApiTest'` on megalan. Expected: 4 tests PASS.

---

### Task 3: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:** Consumes Tasks 1–2. Produces documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table, add one row after **Conciliation**:

```markdown
| **Observability** | Liveness/readiness probes and a Prometheus scrape on a dedicated management port; lifecycle counters for intents, payouts, refunds, and webhook deliveries |
```

In the Status list, append after the M23 line:

```markdown
- [x] M24 — Observability (health probes, Prometheus metrics)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M24 observability complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify` via the Global Constraints command, minus `-Dtest`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
