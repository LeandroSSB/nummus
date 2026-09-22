# M13 — Operational Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Uniform fail-fast configuration validation across all `nummus.*` properties, the two-layer future-dated-ingest stall guard, sub-millisecond `expiresIn` and body-size overflow guards, real scheduler failure logging via `TransactionTemplate`, shared test fixtures, and the M12 review's missing test pins.

**Architecture:** Constraints go on the five `@ConfigurationProperties` records (`@Validated` + jakarta annotations — the classpath already has validation via controller `@Valid`); the stall guard validates `to` against a new `max-window-ahead` property in `ConciliationService` and warns in the worker when `max(period_to)` outruns the lagged now; the worker's transaction boundary moves from a dead-under-self-invocation shape to an injected `TransactionTemplate`; a static `testutils` fixture class absorbs the sixteen-times-copied REST helpers.

**Tech Stack:** Java 25, Spring Boot (`@ConfigurationProperties` + `@Validated`, `ApplicationContextRunner`, `TransactionTemplate`), PostgreSQL via `JdbcClient` + Flyway, JUnit 5 + Testcontainers, MockMvc, logback `ListAppender`.

**Spec:** `docs/superpowers/specs/2026-09-21-m13-operational-hardening-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m13-hardening`, replace `<Test>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m13-hardening origin/worktree-m13-hardening && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Test>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once.
- **TDD strictly:** failing test (remote RED), implement, focused GREEN, full verify, commit.
- **No new dependencies** (validation and spring-boot-test are already present).
- **Baseline: 314 tests, all green** on `main` at plan-writing time. Running totals per task are stated in each GREEN step.
- **Worktree:** execution starts from a worktree on branch `worktree-m13-hardening`. Never commit on `main`.
- Standing test idioms (M12): loopback `http://127.0.0.1:9/<tag>-<uuid>` endpoint URLs, `"Bearer " + secret`, `$.publicId`, shared-container delta counting and `@AfterAll` backdating.

## File Map (final state after all tasks)

```
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookProperties.java        (modify, T1)
src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitProperties.java      (modify, T1)
src/main/java/com/leandrossb/nummus/interfaces/HttpProperties.java                     (modify, T1)
src/main/java/com/leandrossb/nummus/merchants/application/ApiKeyProperties.java        (modify, T1)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationProperties.java (modify, T1/T2)
src/test/java/com/leandrossb/nummus/config/PropertiesValidationTest.java               (new, T1 — plain, no container)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java  (modify, T2)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationWorker.java   (modify, T2/T4)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationStore.java    (modify, T2)
src/main/java/com/leandrossb/nummus/conciliation/infrastructure/JdbcClientConciliationStore.java (modify, T2)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationStallGuardTest.java       (new, T2)
src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysServiceImpl.java      (modify, T3)
src/main/java/com/leandrossb/nummus/merchants/application/InvalidKeyExpiryException.java (modify, T3)
src/test/java/com/leandrossb/nummus/merchants/SubMillisecondExpiryTest.java            (new, T3)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationWorkerTest.java           (modify, T4 — re-pin failure path)
src/test/java/com/leandrossb/nummus/testutils/ApiDrivers.java                          (new, T5)
src/test/java/com/leandrossb/nummus/webhooks/OperatorEventCatalogTest.java             (new, T5 — cross-catalog + reverse isolation + operator pagination pins)
README.md, docs/m2-backlog.md, docs/superpowers/specs/2026-09-21-m12-conciliation-automation-design.md (modify, T6)
16 test suites switch their copied helpers to ApiDrivers (T5)
```

---

### Task 1: Configuration validation — `@Validated` everywhere (TDD)

**Files:**
- Modify: the five properties records (paths above)
- Test: `src/test/java/com/leandrossb/nummus/config/PropertiesValidationTest.java`

**Interfaces:**
- Produces: every record annotated `@Validated` with the constraint set below; `ConciliationProperties` gains `@DefaultValue("PT5M") @Positive Duration maxWindowAhead` (Task 2 consumes it). Boot fails on any violated constraint at binding time.

Constraints per the spec's table:
- `WebhookProperties`: `maxAttempts @Min(1)`, `backoffBase @Positive`, `batchSize @Min(1)`, `retentionDays @Min(0)`
- `RateLimitProperties`: `merchantCapacity @Min(1)`, `merchantRefillPerSecond @Min(0)`, `operatorCapacity @Min(1)`, `operatorRefillPerSecond @Min(0)`
- `HttpProperties`: `maxBodyBytes @Min(1)` + `@Max(2147483646)`
- `ApiKeyProperties`: `rotationGrace @Positive`
- `ConciliationProperties`: `pollDelayMs @Min(1)`, `initialDelayMs @Min(1)`, `windowLag @PositiveOrZero`, `maxWindowAhead @Positive`

- [ ] **Step 1: Write the failing test** (plain JUnit + `ApplicationContextRunner`; NO container, NO Spring Boot app context)

```java
package com.leandrossb.nummus.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.conciliation.application.ConciliationProperties;
import com.leandrossb.nummus.interfaces.HttpProperties;
import com.leandrossb.nummus.interfaces.ratelimit.RateLimitProperties;
import com.leandrossb.nummus.merchants.application.ApiKeyProperties;
import com.leandrossb.nummus.webhooks.application.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Degenerate configuration fails startup — one binding-error per knob,
 *  tested against the properties infrastructure alone (no containers). */
class PropertiesValidationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
          ValidationAutoConfiguration.class))
      .withUserConfiguration(AllProperties.class);

  @Configuration
  @EnableConfigurationProperties({WebhookProperties.class, RateLimitProperties.class,
      HttpProperties.class, ApiKeyProperties.class, ConciliationProperties.class})
  static class AllProperties {
  }

  private void failsWith(String... pairs) {
    runner.withPropertyValues(pairs).run(context -> {
      assertTrue(context.hasFailed(), "boot must fail for " + String.join(",", pairs));
      assertTrue(context.getStartupFailure().getMessage().contains("Binding to target")
          || context.getStartupFailure().getMessage().contains("failed"),
          "the failure must be a binding/validation error");
    });
  }

  @Test
  void webhookKnobsAreValidated() {
    failsWith("nummus.webhooks.max-attempts=0");
    failsWith("nummus.webhooks.backoff-base=PT0S");
    failsWith("nummus.webhooks.batch-size=0");
    failsWith("nummus.webhooks.retention-days=-1");
  }

  @Test
  void rateLimitKnobsAreValidated() {
    failsWith("nummus.ratelimit.merchant-capacity=0");
    failsWith("nummus.ratelimit.merchant-refill-per-second=-1");
    failsWith("nummus.ratelimit.operator-capacity=-3");
    failsWith("nummus.ratelimit.operator-refill-per-second=-2");
  }

  @Test
  void httpKnobsAreValidatedIncludingTheOverflowEdge() {
    failsWith("nummus.http.max-body-bytes=0");
    failsWith("nummus.http.max-body-bytes=2147483647");
  }

  @Test
  void apiKeyKnobsAreValidated() {
    failsWith("nummus.api-keys.rotation-grace=PT0S");
  }

  @Test
  void conciliationKnobsAreValidated() {
    failsWith("nummus.conciliation.poll-delay-ms=0");
    failsWith("nummus.conciliation.initial-delay-ms=-5");
    failsWith("nummus.conciliation.window-lag=PT-1S");
    failsWith("nummus.conciliation.max-window-ahead=PT0S");
  }

  @Test
  void legalEdgesBootFine() {
    runner.withPropertyValues(
            "nummus.conciliation.window-lag=PT0S",
            "nummus.ratelimit.operator-refill-per-second=0",
            "nummus.webhooks.retention-days=0")
        .run(context -> assertFalse(context.hasFailed()));
  }
}
```

(If `ValidationAutoConfiguration`'s package differs in this Boot generation, resolve via the IDE-free route: `org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration` is the Boot 3+ location; if unresolved, register a `LocalValidatorFactoryBean` bean in the nested config instead — same semantics.)

- [ ] **Step 2: Remote RED** — push; FAIL (no `maxWindowAhead` component → compile error on the test's property, and the failure assertions fail because boots succeed).

- [ ] **Step 3: Implement** — annotate each record: add `org.springframework.validation.annotation.Validated` on the record declaration and the jakarta constraint annotations (`jakarta.validation.constraints.Min/Max/Positive/PositiveOrZero`) on the components per the table. `ConciliationProperties` becomes:

```java
@ConfigurationProperties(prefix = "nummus.conciliation")
@Validated
public record ConciliationProperties(
    @DefaultValue("300000") @Min(1) long pollDelayMs,
    @DefaultValue("60000") @Min(1) long initialDelayMs,
    @DefaultValue("PT30S") @PositiveOrZero Duration windowLag,
    @DefaultValue("PT5M") @Positive Duration maxWindowAhead) {
}
```

(The other four follow the same one-line-per-component pattern.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=PropertiesValidationTest` → 6/6; full `verify` → BUILD SUCCESS, **323 tests** (314 + 9: the six methods count 18 property probes inside 6 @Test methods — the suite adds 6 tests, and Task 3 adds its share later; if your count differs from 323, recount YOUR @Test methods and report the true total).

**Correction for the implementer:** the running-total arithmetic above double-counts. Authoritative: this task adds exactly the `@Test` methods you wrote (6), so the full-verify total is **320** (314 + 6). All later tasks' totals are computed from 320.

- [ ] **Step 5: Commit** — `feat: validate all configuration at startup` + trailer; push.

---

### Task 2: Stall guard — route rejection + warn-on-stall (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java` (route guard)
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationWorker.java` (warn-on-stall)
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationStore.java` + `infrastructure/JdbcClientConciliationStore.java` (`latestReportEnd`)
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationStallGuardTest.java`

**Interfaces:**
- Consumes: Task 1's `ConciliationProperties.maxWindowAhead()`.
- Produces: `ConciliationService.ingest` rejects `to` after `now().plus(maxWindowAhead)` with `IllegalArgumentException` (→ 400, no report row); `ConciliationStore.latestReportEnd()` returns `Optional<Instant>` (`select max(period_to) from conciliation.settlement_report`); the worker warns once per stall episode.

- [ ] **Step 1: Write the failing test** (extends `IntegrationTestBase`, `@AutoConfigureMockMvc`; use the `ConciliationAlertsTest` idioms — clean settle recipe, loopback URLs, delta counting)

```java
package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.leandrossb.nummus.conciliation.application.ConciliationWorker;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationStallGuardTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private ConciliationWorker worker;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private int reportCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        var rs = st.executeQuery("select count(*) from conciliation.settlement_report")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void futureDatedWindowEndIsRejectedWithoutARow() throws Exception {
    int before = reportCount();
    String to = Instant.now().plusSeconds(3600).toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now().minusSeconds(60) + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isBadRequest());
    Assertions.assertEquals(before, reportCount());
  }

  @Test
  void withinSlackWindowEndsAreAccepted() throws Exception {
    // 30s ahead is inside the default PT5M slack: not a 400 (the window's own
    // semantics decide the rest).
    String to = Instant.now().plusSeconds(30).toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now().minusSeconds(60) + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void stalledTicksWarnOncePerEpisode() throws Exception {
    // Plant a future-dated report directly (the route guard now blocks the
    // HTTP path; SQL is how a legacy row would exist).
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into conciliation.settlement_report "
          + "(public_id, period_from, period_to, status, matched_count, amount_mismatched_count, "
          + "missing_internal_count, missing_external_count) values ('"
          + UUID.randomUUID() + "', now() - interval '1 minute', now() + interval '1 hour', "
          + "'CONCILED', 0, 0, 0, 0)");
    }
    Logger workerLogger = (Logger) LoggerFactory.getLogger(ConciliationWorker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    workerLogger.addAppender(appender);
    try {
      worker.tick();
      worker.tick();
    } finally {
      workerLogger.detachAppender(appender);
    }
    long stalls = appender.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
        .filter(e -> e.getFormattedMessage().contains("period_to")).count();
    Assertions.assertEquals(1, stalls, "one warn per stall episode, not per tick");
    // Clean up: backdate the planted row so later suites are unaffected.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.settlement_report set period_to = now() - interval '2 hours' "
          + "where period_to > now()");
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (the future-dated POST returns 201, not 400; no warn events exist).

- [ ] **Step 3: Implement**

`ConciliationService` — constructor gains `ConciliationProperties properties`; first thing in `ingest` (and only there — the worker's windows are lag-backed by construction):

```java
    if (to.isAfter(Instant.now().plus(properties.maxWindowAhead()))) {
      throw new IllegalArgumentException(
          "window end is too far in the future: " + to + " (allowed ahead: "
              + properties.maxWindowAhead() + ")");
    }
```

`ConciliationStore` + impl:

```java
  /** The latest report's period_to, if any — the stall detector's input. */
  Optional<Instant> latestReportEnd();
```

```java
  @Override
  public Optional<Instant> latestReportEnd() {
    return jdbc.sql("select max(period_to) from conciliation.settlement_report")
        .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).optional();
  }
```

`ConciliationWorker` — add the episode-deduplicated warn on the no-op path (Task 4 will restructure the transaction; keep this logic intact through it):

```java
  private volatile boolean stallWarned;

  // in runWindow(), replacing the bare early return:
    if (!start.isBefore(end)) {
      store.latestReportEnd().ifPresentOrElse(latest -> {
        if (latest.isAfter(end)) {
          if (!stallWarned) {
            LOGGER.warn("conciliation ticks are stalled: report period_to {} is ahead of the "
                + "lagged now; automated divergence alerting is paused until wall clock passes it "
                + "or the row is corrected", latest);
            stallWarned = true;
          }
        } else {
          stallWarned = false;
        }
      }, () -> stallWarned = false);
      return;
    }
    stallWarned = false;
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ConciliationStallGuardTest,ConciliationWorkerTest,ConciliationRestApiTest'` → all green (existing suites must not trip the guard — their windows end at or before now); full `verify` → BUILD SUCCESS, **323 tests** (320 + 3).

- [ ] **Step 5: Commit** — `feat: guard future-dated ingests and warn on stalled ticks` + trailer; push.

---

### Task 3: Sub-millisecond `expiresIn` rejection (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysServiceImpl.java` (`requirePositiveExpiry`)
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/InvalidKeyExpiryException.java` (message)
- Test: `src/test/java/com/leandrossb/nummus/merchants/SubMillisecondExpiryTest.java`

**Interfaces:**
- Produces: `requirePositiveExpiry` rejects null-safe positive durations shorter than one millisecond (`toMillis() < 1`); applies to merchant + operator, mint + rotation via the shared validator.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SubMillisecondExpiryTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"SubMs Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void subMillisecondExpiryIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0.0005S\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void oneMillisecondExpiryMintsNormally() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0.001S\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (the sub-ms mint returns 201 — the stillborn-key hazard reproduces).

- [ ] **Step 3: Implement**

```java
  static void requirePositiveExpiry(Duration expiresIn) {
    if (expiresIn != null && (!expiresIn.isPositive() || expiresIn.toMillis() < 1)) {
      throw new InvalidKeyExpiryException();
    }
  }
```

`InvalidKeyExpiryException` message becomes: `"expiresIn must be an ISO-8601 duration of at least one millisecond (e.g. P90D) or omitted"`.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='SubMillisecondExpiryTest,KeyExpiryRestApiTest,KeyRotationRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **325 tests** (323 + 2).

- [ ] **Step 5: Commit** — `fix: reject sub-millisecond key expiry` + trailer; push.

---

### Task 4: `TransactionTemplate` — one real warn per failed tick (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationWorker.java`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationWorkerTest.java` (extend)

**Interfaces:**
- Produces: `tick()` un-annotated, non-transactional; the window body runs inside an injected `TransactionTemplate` (constructed from `PlatformTransactionManager` in the worker's constructor). Failure → one WARN with the original cause, full rollback, no escaped `UnexpectedRollbackException`.

- [ ] **Step 1: Write the failing test** — first READ `ConciliationWorkerTest` and find the existing failure-path test (the M12 review noted the rollback path is exercised "via rejected windows"; if the mechanism is a planted condition, reuse it). Then add/extend one test asserting BOTH: (a) the rollback semantics still hold (no report/event/marker change after the forced failure), and (b) the worker logger's WARN list contains the ORIGINAL cause's message and contains no `UnexpectedRollbackException` event (ListAppender, as in Task 2's stall test). If no deterministic failure mechanism exists in the suite, force one: register a `settlement_report` row whose insert collides is impossible — instead plant `conciliation.ingest_state.last_window_end` such that `start < end` holds, and make `ingestIfAnyLines` fail by SQL-deleting the `conciliation` schema's USAGE grant? NO — use the honest, minimal seam: a `@MockitoBean`-style substitute is not house; instead force a `DataAccessException` by dropping the app role's SELECT on `settlement_report` for the duration of one tick via `adminConnection` (`revoke select on conciliation.settlement_report from nummus_app`) and re-granting in a `finally`. That is deterministic, reversible, and exercises the real path.

```java
  @Test
  void aFailedTickLogsOneRealWarnAndRollsBackEverything() throws Exception {
    // (plant any pending divergence so the tick has real work to roll back —
    //  reuse this suite's settleAndHideExternalCharge helper)
    settleAndHideExternalCharge();
    Logger workerLogger = (Logger) LoggerFactory.getLogger(ConciliationWorker.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    workerLogger.addAppender(appender);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("revoke select on conciliation.settlement_report from nummus_app");
      try {
        worker.tick();
      } finally {
        st.executeUpdate("grant select on conciliation.settlement_report to nummus_app");
      }
    } finally {
      workerLogger.detachAppender(appender);
    }
    var warns = appender.list.stream()
        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
    org.junit.jupiter.api.Assertions.assertEquals(1, warns.size(),
        "exactly one warn, carrying the real cause");
    boolean escaped = appender.list.stream().anyMatch(e ->
        e.getFormattedMessage().contains("UnexpectedRollbackException")
            || e.getThrowableProxy() != null && String.valueOf(e.getThrowableProxy().getClassName())
                .contains("UnexpectedRollbackException"));
    org.junit.jupiter.api.Assertions.assertFalse(escaped);
    // Rollback: the pending divergence produced no report, event, or advance.
    // (assert via this suite's existing report/event count helpers)
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (with today's `@Transactional tick()`, the escaped `UnexpectedRollbackException` surfaces as an ERROR and the WARN count is zero or the exception escapes `tick()` entirely — which fails the test's assumption that `tick()` returns; wrap the `worker.tick()` call in a try/catch for the RED phase only if needed to reach the assertion, and note it).

- [ ] **Step 3: Implement**

```java
  private final TransactionTemplate transactions;

  public ConciliationWorker(ConciliationService conciliation, ConciliationStore store,
      ConciliationProperties properties, PlatformTransactionManager transactionManager) {
    this.conciliation = conciliation;
    this.store = store;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${nummus.conciliation.poll-delay-ms:300000}",
      initialDelayString = "${nummus.conciliation.initial-delay-ms:60000}")
  public void tick() {
    try {
      transactions.executeWithoutResult(status -> runWindow());
    } catch (Exception e) {
      LOGGER.warn("conciliation ingest tick failed; the window will retry", e);
    }
  }
```

(Remove the `@Transactional` annotation and its import; `runWindow` keeps Task 2's stall-warn logic verbatim. The template commits report + digest + marker together — identical atomicity, real swallow.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ConciliationWorkerTest,ConciliationStallGuardTest'` → all green; full `verify` → BUILD SUCCESS, **326 tests** (325 + 1).

- [ ] **Step 5: Commit** — `refactor: run the ingest tick in a transaction template` + trailer; push.

---

### Task 5: Shared fixtures + the M12-review pins (TDD for the pins; refactor for the fixtures)

**Files:**
- Create: `src/test/java/com/leandrossb/nummus/testutils/ApiDrivers.java`
- Modify: the 16 suites that copy `operatorAuth()` (grep lists them: ConciliationAlertsTest, ConciliationRestApiTest, ConciliationWorkerTest, IdempotencyMerchantNamespaceTest, RateLimitFilterTest, ApiKeyExpiryAuthTest, FeeRestApiTest, KeyExpiryRestApiTest, KeyRotationRestApiTest, MerchantAuthFilterTest, MerchantScopingTest, MerchantsRestApiTest, OperatorGatingTest, OperatorWebhookDeliveriesRestApiTest, OperatorWebhookEndpointsRestApiTest, WebhookAudienceTest)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/OperatorEventCatalogTest.java`

**Interfaces:**
- Produces: `ApiDrivers` static helpers — `operatorAuth(OperatorKeysService)`, `createMerchantAndGetKey(MockMvc, String operatorAuth, String name)`, `loopbackUrl(String tag)`, plus (as available per suite today) `registerEndpointAndGetId`/`settlePayment` variants. Each helper keeps EXACTLY the behavior of the copies it replaces (loopback URLs, `$.apiKey.secret`, `"Bearer "` prefix, unique Idempotency-Keys).

- [ ] **Step 1: Write the failing pins test** (RED — none of these behaviors are pinned today)

```java
package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The M12 review's missing pins: catalogs are disjoint per namespace,
 *  namespaces are isolated in BOTH directions, and the operator pagination
 *  controller is pinned independently of its merchant mirror. */
@AutoConfigureMockMvc
class OperatorEventCatalogTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private WebhookStore webhookStore;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Catalog Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void catalogsAreDisjointPerNamespace() throws Exception {
    // Operator surface rejects a payment type...
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/catalog-" + UUID.randomUUID()
                + "\",\"eventTypes\":[\"payment_intent.settled\"]}"))
        .andExpect(status().isBadRequest());
    // ...and the merchant surface rejects the conciliation type.
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + createMerchantAndGetKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/catalog-" + UUID.randomUUID()
                + "\",\"eventTypes\":[\"conciliation.report_open\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void merchantSurfaceNeverSeesOperatorEndpoints() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/reverse-" + UUID.randomUUID() + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String operatorEndpointId = com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(), "$.publicId");
    String merchantBearer = createMerchantAndGetKey();
    MvcResult listed = mockMvc.perform(get("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantBearer))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(
        listed.getResponse().getContentAsString().contains(operatorEndpointId));
    mockMvc.perform(get("/v1/webhook-endpoints/" + operatorEndpointId)
            .header("Authorization", "Bearer " + merchantBearer))
        .andExpect(status().isNotFound());
  }

  @Test
  void operatorPaginationIsIndependentlyPinned() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/pins-" + UUID.randomUUID() + "\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    String endpointId = com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(), "$.publicId");
    String auth = operatorAuth();
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("limit", "101"))
        .andExpect(status().isBadRequest());
    webhookStore.insertEvent(UUID.randomUUID(), null, "conciliation.report_open",
        "{\"pins\":1}", Instant.now());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", auth).param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (none of the four pinned behaviors has a test asserting it today; the reverse-isolation 404 and both 400s will pass structurally — the RED is the missing-suite compile pass plus any that genuinely fail; verify at least one fails or report that all pass structurally, in which case the pins are still the deliverable and the task proceeds — pins for currently-correct behavior are legitimately green-on-arrival; note it in the report).

- [ ] **Step 3: Implement** — create `ApiDrivers` with the shared static helpers (each an exact behavioral copy of the best current implementation among the suites — loopback URLs, `Bearer` prefixes, unique keys per call); then refactor the 16 suites to call them, deleting each private copy. Purely mechanical; the focused suites after refactor must stay green with unchanged counts.

- [ ] **Step 4: Remote GREEN + verify** — focused: the three webhook operator suites + `MerchantScopingTest` + `ConciliationAlertsTest` (spot checks across the refactor); full `verify` → BUILD SUCCESS, **329 tests** (326 + 3).

- [ ] **Step 5: Commit** — two commits: `test: pin the operator catalog and namespace guarantees` (the new suite), then `refactor: extract the shared REST test drivers` (ApiDrivers + the 16 suites); both + trailer; push.

---

### Task 6: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M13 — Operational hardening` (no capability-row changes: hardening adds no capability).
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M13 design

M13 hardened operations: uniform startup validation on every `nummus.*`
knob (one binding error instead of a permanently-429ing or overflow-prone
runtime), the two-layer future-dated-ingest stall guard (route 400 plus a
once-per-episode scheduler warn), sub-millisecond `expiresIn` rejection,
`TransactionTemplate` failure logging with real atomicity, shared REST test
drivers, and the M12 review's pins. Known bounds, deliberate:

- **Validation is startup-time only** — runtime re-binding or refresh of
  properties stays out of scope.
- **The stall warn is per-process** — a second instance warns independently
  (the workers' single-process stance).
- **No pagination-helper extraction** — the two mirrored controllers are
  pinned independently; extraction waits for a third copy.
```

- Modify: `docs/superpowers/specs/2026-09-21-m12-conciliation-automation-design.md` — align the event payload sketch with the shipped flat envelope: replace the nested `"window": { "from": …, "to": … }` object with flat `from`/`to` inside the standard `{id, type, occurredAt, data}` wrapper (the M12 review's doc rider).

- [ ] **Step 1: Remote full verify** — `Tests run: 329, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog/spec edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M13 operational hardening complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| Uniform Bean Validation (+ overflow edge, legal edges) | 1 |
| Stall guard: route 400 + warn-once + `maxWindowAhead` | 2 |
| Sub-millisecond `expiresIn` rejection | 3 |
| `TransactionTemplate` tick + real warn | 4 |
| Shared fixtures + cross-catalog/reverse-isolation/operator-pagination pins | 5 |
| README + backlog + M12 spec rider + final gate (329) | 6 |
