# M12 — Conciliation Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scheduled, self-healing settlement-report re-ingest plus operator-owned webhook endpoints that receive a `conciliation.report_open` digest whenever any ingest lands OPEN — reusing the transactional outbox and all of its M5/M10/M11 hardening, and closing a latent cross-tenant delivery leak found during recon.

**Architecture:** Operator endpoints are rows in `webhooks.webhook_endpoint` with `merchant_public_id = NULL` (the M7 idempotency-namespace trick); every merchant-scoped query generalizes with `is not distinct from`, so one worker, one policy, one retention job serves both namespaces. The publish path gains an audience (the event's merchant id, or NULL for operators) — fixing today's unscoped cross-join. A single-row `conciliation.ingest_state` drives tumbling windows whose start self-heals against manual ingests and whose end holds back a 30s lag for the M6 clock-skew boundary.

**Tech Stack:** Java 25, Spring Boot (`@Scheduled` fixedDelay workers, `@ConfigurationProperties` records), PostgreSQL via `JdbcClient` + Flyway, JUnit 5 + Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-21-m12-conciliation-automation-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch is `worktree-m12-conciliation`, replace `<Test>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m12-conciliation origin/worktree-m12-conciliation && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Test>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once.
- **TDD strictly:** failing test (remote RED), implement, focused GREEN, full verify, commit.
- **No new dependencies.** Namespace unification uses PostgreSQL `is not distinct from` — no schema fan-out, no parallel services.
- **Baseline: 297 tests, all green** on `main` at plan-writing time. Running totals per task are stated in each GREEN step; if a fix wave changes a count, the controller amends the remaining briefs.
- **Worktree:** execution starts from a worktree on branch `worktree-m12-conciliation` (created at execution time). Never commit on `main`.
- House SQL note: Spring's named-parameter parser skips PostgreSQL `::` casts — `is not distinct from`, `make_interval`, and `'infinity'::timestamptz` (already shipped) are all safe.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V15__operator_webhooks_and_conciliation_state.sql (new, Task 1)
src/main/java/com/leandrossb/nummus/payments/application/IntentLifecycleEvent.java (modify, Task 2)
src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java (modify, Task 2)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java (modify, Tasks 2/3/4)
src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java (modify, Tasks 2/3/4)
src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxIntentLifecycleEvents.java (modify, Task 2)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java (modify, Task 3)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookEndpointsController.java (new, Task 3)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookDeliveriesController.java (new, Task 4)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationEventTypes.java (new, Task 3)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationAlerts.java (new, Task 5)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxConciliationAlerts.java (new, Task 5)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationProperties.java (new, Task 6)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationWorker.java (new, Task 6)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationStore.java (modify, Task 6)
src/main/java/com/leandrossb/nummus/conciliation/infrastructure/JdbcClientConciliationStore.java (modify, Task 6)
src/test/java/com/leandrossb/nummus/testutils/IntegrationTestBase.java (modify, Task 6 — two property pins)
src/test/java/com/leandrossb/nummus/webhooks/WebhookAudienceTest.java (new, Task 2)
src/test/java/com/leandrossb/nummus/webhooks/OperatorWebhookEndpointsRestApiTest.java (new, Task 3)
src/test/java/com/leandrossb/nummus/webhooks/OperatorWebhookDeliveriesRestApiTest.java (new, Task 4)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationAlertsTest.java (new, Task 5)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationWorkerTest.java (new, Task 6)
src/test/java/com/leandrossb/nummus/conciliation/OperatorWebhookSchemaTest.java (new, Task 1)
README.md, docs/m2-backlog.md (modify, Task 7)
```

---

### Task 1: `V15` — nullable endpoint namespace + ingest state

**Files:**
- Create: `src/main/resources/db/migration/V15__operator_webhooks_and_conciliation_state.sql`
- Test: `src/test/java/com/leandrossb/nummus/conciliation/OperatorWebhookSchemaTest.java`

**Interfaces:**
- Produces: `webhooks.webhook_endpoint.merchant_public_id` nullable, no default (Tasks 3/4 rely on NULL-namespace rows); `conciliation.ingest_state` single row (`id=1`) seeded with the migration-time `now()` (Task 6 relies on it).

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.conciliation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorWebhookSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void endpointMerchantColumnAcceptsNullAndHasNoDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // NULL-merchant insert succeeds and stays NULL (operator namespace),
      // and no seed default fires.
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret, event_types, "
          + "merchant_public_id) values ('" + UUID.randomUUID() + "', 'https://ops.example/null-probe', "
          + "'whsec_probe', '[]'::jsonb, null)");
      try (ResultSet rs = st.executeQuery("select merchant_public_id from webhooks.webhook_endpoint "
          + "where url = 'https://ops.example/null-probe'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("merchant_public_id"));
      }
      // Omitting the column entirely is also accepted (no default, not NOT NULL).
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret, event_types) "
          + "values ('" + UUID.randomUUID() + "', 'https://ops.example/omitted-probe', "
          + "'whsec_probe', '[]'::jsonb)");
      try (ResultSet rs = st.executeQuery("select merchant_public_id from webhooks.webhook_endpoint "
          + "where url = 'https://ops.example/omitted-probe'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("merchant_public_id"));
      }
    }
  }

  @Test
  void ingestStateIsASingleSeededRowTheAppRoleCanAdvance() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("select last_window_end, updated_at "
          + "from conciliation.ingest_state where id = 1")) {
        assertTrue(rs.next());
        assertNotNull(rs.getObject("last_window_end"));
        assertNotNull(rs.getObject("updated_at"));
      }
      // Exactly one row: the check constraint rejects a second.
      assertThrows(java.sql.SQLException.class, () -> st.executeUpdate(
          "insert into conciliation.ingest_state (id, last_window_end) values (2, now())"));
    }
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("update conciliation.ingest_state "
          + "set last_window_end = now(), updated_at = now() where id = 1"));
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=OperatorWebhookSchemaTest` → FAIL (`column "merchant_public_id" of relation "webhook_endpoint" violates not-null constraint` on the inserts; `relation "conciliation.ingest_state" does not exist` on the state queries).

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V15__operator_webhooks_and_conciliation_state.sql`:

```sql
-- M12 conciliation automation: the operator webhook namespace and the
-- scheduled-ingest window state. Operator endpoints are webhook_endpoint
-- rows with merchant_public_id NULL -- the same namespace trick
-- idempotency_keys has used since M7 -- so one delivery worker, one URL
-- policy, and one retention job serve both audiences. The seed default on
-- merchant_public_id was V10 migration-era backfill; both registration
-- paths now pass explicit values, so the default goes away.

alter table webhooks.webhook_endpoint
  alter column merchant_public_id drop not null,
  alter column merchant_public_id drop default;

create table conciliation.ingest_state (
  id              int primary key check (id = 1),
  last_window_end timestamptz not null,
  updated_at      timestamptz not null default now()
);

insert into conciliation.ingest_state (id, last_window_end) values (1, now());

grant select, insert, update on conciliation.ingest_state to nummus_app;
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=OperatorWebhookSchemaTest` → 2/2; full `verify` → BUILD SUCCESS, **299 tests** (297 + 2).

- [ ] **Step 5: Commit** — `feat: add the operator webhook namespace and ingest state (V15)` + trailer; push.

---

### Task 2: Audience-scoped fan-out — the cross-tenant leak fix (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/IntentLifecycleEvent.java` (gains `merchantPublicId`)
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java` (three publish sites + `toEvent`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java` (`insertEvent` gains the audience)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (fan-out predicate)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxIntentLifecycleEvents.java` (passes the audience)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookAudienceTest.java`

**Interfaces:**
- Produces: `IntentLifecycleEvent(UUID merchantPublicId, String type, UUID publicId, UUID accountPublicId, Money amount, String status, UUID chargePublicId, Instant settledAt, UUID journalTransactionPublicId, Money fee, Money netAmount)` — merchant id FIRST; every construction site updated in this task. `WebhookStore.insertEvent(UUID eventPublicId, UUID audienceMerchant, String type, String payload, Instant occurredAt)` — audience NULL means operators (Task 5's adapter passes null). Task 3/4 tests build on the scoped fan-out.

- [ ] **Step 1: Write the failing test**

`WebhookAudienceTest` — follow `WebhookPublishTest` for the settle flow (merchant → account → intent → simulator pay → `GET /v1/payment-intents/{id}` settles and publishes). Two merchants, one endpoint each; only the settling merchant's endpoint may receive a delivery.

```java
package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Today's fan-out cross-joins every ACTIVE endpoint: merchant B receives
 *  merchant A's payment events. This pins the audience-scoped fix. */
@AutoConfigureMockMvc
class WebhookAudienceTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String registerEndpointAndGetId(String bearer) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://" + UUID.randomUUID() + ".example/hook\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.endpointId");
  }

  private String settleSomething(String bearer) throws Exception {
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Audience Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountLocation = opened.getResponse().getHeader("Location");
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intent.getResponse().getHeader("Location");
    String intentId = intentLocation.substring(intentLocation.lastIndexOf('/') + 1);
    // The charge id travels on the intent; pay it in the simulator, then GET settles.
    MvcResult fetched = mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk()).andReturn();
    String chargeId = com.jayway.jsonpath.JsonPath.read(fetched.getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk());
    return intentId;
  }

  @Test
  void settledEventDeliversOnlyToTheOwningMerchantsEndpoint() throws Exception {
    String a = createMerchantAndGetKey("Audience A");
    String b = createMerchantAndGetKey("Audience B");
    String aEndpoint = registerEndpointAndGetId(a);
    String bEndpoint = registerEndpointAndGetId(b);

    settleSomething(a);

    MvcResult aDeliveries = mockMvc.perform(get("/v1/webhook-endpoints/" + aEndpoint + "/deliveries")
            .header("Authorization", "Bearer " + a))
        .andExpect(status().isOk()).andReturn();
    MvcResult bDeliveries = mockMvc.perform(get("/v1/webhook-endpoints/" + bEndpoint + "/deliveries")
            .header("Authorization", "Bearer " + b))
        .andExpect(status().isOk()).andReturn();
    int aCount = com.jayway.jsonpath.JsonPath.read(aDeliveries.getResponse().getContentAsString(), "$.length()");
    int bCount = com.jayway.jsonpath.JsonPath.read(bDeliveries.getResponse().getContentAsString(), "$.length()");
    Assertions.assertTrue(aCount >= 1, "owner must receive the settled event");
    Assertions.assertEquals(0, bCount, "another merchant's endpoint must receive nothing");
  }
}
```

(If `WebhookPublishTest` shows a different field name for the charge id or settle call shape, follow THAT suite's exact idiom — the invariant under test is the isolation, not the setup calls.)

- [ ] **Step 2: Remote RED** — push; FAIL (`bCount` is 1: the leak reproduces).

- [ ] **Step 3: Implement**

`IntentLifecycleEvent.java` — add `UUID merchantPublicId` as the FIRST component; extend the javadoc: "The owning merchant — the delivery audience."

`PaymentsServiceImpl.java` — `toEvent` gains the merchant and every call site passes `merchantPublicId` (in scope at all three: `get(...)`'s expire branch, its FAILED branch, and `settle(intent, merchantPublicId)`):

```java
  private static IntentLifecycleEvent toEvent(UUID merchantPublicId, String type, PaymentIntent intent,
      Money fee, Money netAmount) {
    return new IntentLifecycleEvent(merchantPublicId, type, intent.publicId(), intent.accountPublicId(),
        intent.amount(), intent.status().name(), intent.chargePublicId(),
        intent.settledAt(), intent.journalTransactionPublicId(), fee, netAmount);
  }
```

`WebhookStore.java`:

```java
  /** Fans out inside the caller's transaction to every ACTIVE endpoint in the
   * audience: the event's merchant, or — when audienceMerchant is null — the
   * operator namespace (merchant_public_id is null). One delivery row per
   * endpoint whose event_types is empty (all types) or contains the type. */
  void insertEvent(UUID eventPublicId, UUID audienceMerchant, String type, String payload, Instant occurredAt);
```

`JdbcClientWebhookStore.java` — `insertEvent` gains the `.param("audienceMerchant", audienceMerchant)` bind and the fan-out predicate:

```java
    jdbc.sql("""
        insert into webhooks.webhook_delivery (event_id, endpoint_id)
        select e.id, p.id
        from webhooks.webhook_event e
        cross join webhooks.webhook_endpoint p
        where e.public_id = :eventPublicId
          and p.status = 'ACTIVE'
          and p.merchant_public_id is not distinct from :audienceMerchant
          and (jsonb_array_length(p.event_types) = 0 or p.event_types @> to_jsonb(:type))
        """)
```

(`is not distinct from` binds NULL audience to the operator namespace and non-NULL to the owning merchant — one predicate, both audiences.)

`OutboxIntentLifecycleEvents.java` — `store.insertEvent(eventId, event.merchantPublicId(), event.type(), payload, Instant.now())`. The wire payload is unchanged — no new fields.

Ripples (compile-driven): any direct `new IntentLifecycleEvent(...)` or `insertEvent(...)` call in tests gains the merchant argument (grep both; `WebhookPublishTest` and any unit suite constructing events — pass the seed/owning merchant id used by the surrounding setup).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='WebhookAudienceTest,WebhookPublishTest'` → all green; full `verify` → BUILD SUCCESS, **300 tests** (299 + 1).

- [ ] **Step 5: Commit** — `fix: scope webhook fan-out to the owning merchant` + trailer; push.

---

### Task 3: Operator webhook endpoints (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationEventTypes.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookEndpointsController.java`
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java` (operator registration + shared private mint)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (scoping predicates → `is not distinct from`) + `WebhookStore.java` javadocs only
- Test: `src/test/java/com/leandrossb/nummus/webhooks/OperatorWebhookEndpointsRestApiTest.java`

**Interfaces:**
- Consumes: Task 1's nullable column; Task 2's `is not distinct from` fan-out pattern.
- Produces: `ConciliationEventTypes.REPORT_OPEN = "conciliation.report_open"` (+ `ALL`); `WebhookEndpointsService.registerOperator(URI url, List<String> eventTypes)`; NULL-namespace behavior of `list`/`get`/`delete` (call them with `null`); REST `POST|GET /v1/operator/webhook-endpoints`, `DELETE /v1/operator/webhook-endpoints/{id}` (operator-authenticated). Tasks 4/5/6 rely on all of these.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class OperatorWebhookEndpointsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private String registerOperatorEndpoint(String url, String typesJson) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + url + "\",\"eventTypes\":" + typesJson + "}"))
        .andExpect(status().isCreated()).andReturn();
    return created.getResponse().getContentAsString();
  }

  @Test
  void registerCreatesWithSecretOnceAndPolicyRejectsUnsafeUrls() throws Exception {
    String body = registerOperatorEndpoint("https://ops.example/hook", "[\"conciliation.report_open\"]");
    org.junit.jupiter.api.Assertions.assertTrue(
        com.jayway.jsonpath.JsonPath.read(body, "$.secret").toString().startsWith("whsec_"));
    String endpointId = com.jayway.jsonpath.JsonPath.read(body, "$.endpointId");
    // The secret never comes back: listing carries ids and prefixes only.
    MvcResult listed = mockMvc.perform(get("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth()))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(
        listed.getResponse().getContentAsString().contains(endpointId));
    org.junit.jupiter.api.Assertions.assertFalse(
        listed.getResponse().getContentAsString().contains("whsec_"));
    // The M10 URL policy applies to the operator namespace too.
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://192.168.0.9/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownEventTypesAreRejected() throws Exception {
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://ops.example/hook\",\"eventTypes\":[\"made.up.event\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void namespacesAreIsolatedInBothDirections() throws Exception {
    // Operator listing never shows merchant endpoints...
    String operatorAuth = operatorAuth();
    MvcResult merchantEndpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", seedMerchantBearer())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://" + UUID.randomUUID() + ".example/m-hook\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantEndpointId = com.jayway.jsonpath.JsonPath.read(
        merchantEndpoint.getResponse().getContentAsString(), "$.endpointId");
    MvcResult operatorList = mockMvc.perform(get("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertFalse(
        operatorList.getResponse().getContentAsString().contains(merchantEndpointId));
    // ...and the operator route 404s a merchant endpoint id.
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + merchantEndpointId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteIsSoftAndThenUnknown() throws Exception {
    String body = registerOperatorEndpoint("https://ops.example/gone", "[]");
    String endpointId = com.jayway.jsonpath.JsonPath.read(body, "$.endpointId");
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/v1/operator/webhook-endpoints/" + endpointId)
            .header("Authorization", operatorAuth()))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId)
            .header("Authorization", operatorAuth()))
        .andExpect(status().isNotFound());
  }

  private String seedMerchantBearer() throws Exception {
    MvcResult merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Namespace Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(merchant.getResponse().getContentAsString(), "$.apiKey.secret");
  }
}
```

(If `CreateEndpointResponse` uses a different id field name than `endpointId`, follow the actual DTO — mirror what the merchant suite asserts.)

- [ ] **Step 2: Remote RED** — push; FAIL (404 on `/v1/operator/webhook-endpoints` routes).

- [ ] **Step 3: Implement**

`ConciliationEventTypes.java`:

```java
package com.leandrossb.nummus.conciliation.application;

import java.util.Set;

/** Single source of the conciliation event type strings (the operator
 *  webhook catalog mirrors this). */
public final class ConciliationEventTypes {

  public static final String REPORT_OPEN = "conciliation.report_open";
  public static final Set<String> ALL = Set.of(REPORT_OPEN);

  private ConciliationEventTypes() {
  }
}
```

`WebhookEndpointsService.java` — extract the shared mint and add the operator registration; merchant registration keeps `IntentEventTypes.ALL`, operator registration validates against `ConciliationEventTypes.ALL`:

```java
  public WebhookEndpoint register(UUID merchantPublicId, URI url, List<String> eventTypes) {
    return doRegister(merchantPublicId, url, eventTypes, IntentEventTypes.ALL);
  }

  /** Operator namespace (merchant_public_id NULL): conciliation alerts. */
  public WebhookEndpoint registerOperator(URI url, List<String> eventTypes) {
    return doRegister(null, url, eventTypes, ConciliationEventTypes.ALL);
  }

  private WebhookEndpoint doRegister(UUID merchantPublicId, URI url, List<String> eventTypes,
      java.util.Set<String> catalog) {
    WebhookUrlPolicy.check(url);
    List<String> types = eventTypes == null ? List.of() : eventTypes;
    List<String> unknown = types.stream().filter(t -> !catalog.contains(t)).toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown event types: " + unknown);
    }
    byte[] secretBytes = new byte[32];
    random.nextBytes(secretBytes);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    return store.insertEndpoint(new WebhookEndpoint(merchantPublicId, UUID.randomUUID(), url, secret,
        types, com.leandrossb.nummus.webhooks.domain.EndpointStatus.ACTIVE, Instant.now()));
  }
```

Import `com.leandrossb.nummus.conciliation.application.ConciliationEventTypes` (webhooks→conciliation follows the existing webhooks→payments catalog dependency; no cycle — conciliation never imports webhooks).

`JdbcClientWebhookStore.java` — the four scoping predicates change from `= :merchantPublicId` to `is not distinct from :merchantPublicId` (with a one-line SQL comment: `-- null audience = the operator namespace`): `listActiveEndpoints`, `findActiveEndpoint`, `markEndpointDeleted` (this task) — `listDeliveries`/`requeueFailedDelivery` land in Task 4 with their controller.

`OperatorWebhookEndpointsController.java` — mirror `WebhookEndpointsController`, operator-authenticated, no `@Idempotent` differences (POST carries it, like the merchant surface):

```java
package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.interfaces.dto.CreateEndpointRequest;
import com.leandrossb.nummus.webhooks.interfaces.dto.CreateEndpointResponse;
import com.leandrossb.nummus.webhooks.interfaces.dto.EndpointResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator self-serve surface for the operator webhook namespace: operators
 *  are role-level, so any operator key manages the shared endpoint set. */
@RestController
@RequestMapping("/v1/operator/webhook-endpoints")
class OperatorWebhookEndpointsController {

  private final WebhookEndpointsService endpoints;

  OperatorWebhookEndpointsController(WebhookEndpointsService endpoints) {
    this.endpoints = endpoints;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateEndpointResponse> create(AuthenticatedOperator operator,
      @Valid @RequestBody CreateEndpointRequest request) {
    var endpoint = endpoints.registerOperator(URI.create(request.url()), request.eventTypes());
    return ResponseEntity
        .created(URI.create("/v1/operator/webhook-endpoints/" + endpoint.publicId()))
        .body(CreateEndpointResponse.from(endpoint));
  }

  @GetMapping
  java.util.List<EndpointResponse> list(AuthenticatedOperator operator) {
    return endpoints.list(null).stream().map(EndpointResponse::from).toList();
  }

  @GetMapping("/{id}")
  EndpointResponse get(AuthenticatedOperator operator, @PathVariable UUID id) {
    return EndpointResponse.from(endpoints.get(null, id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(AuthenticatedOperator operator, @PathVariable UUID id) {
    endpoints.delete(null, id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='OperatorWebhookEndpointsRestApiTest,WebhookEndpointsRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **304 tests** (300 + 4).

- [ ] **Step 5: Commit** — `feat: add operator webhook endpoints` + trailer; push.

---

### Task 4: Operator deliveries listing + redrive parity (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookDeliveriesController.java`
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (`listDeliveries`, `requeueFailedDelivery` predicates → `is not distinct from`)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/OperatorWebhookDeliveriesRestApiTest.java`

**Interfaces:**
- Consumes: Task 3's operator endpoints + NULL-namespace `get`; Task 2's `insertEvent(eventId, null, ...)` operator fan-out (used by tests to seed deliveries directly through the store).
- Produces: `GET /v1/operator/webhook-endpoints/{id}/deliveries?status=&after=&limit=` (array body, `Next-Cursor` header, limit 1–100, unknown cursor → empty page) and `POST /v1/operator/webhook-deliveries/{id}/redrive` (`@Idempotent`, 202) — both operator-authenticated.

- [ ] **Step 1: Write the failing test**

Seed deliveries without HTTP round-trips: register one operator endpoint via REST, then call the store directly — `@Autowired WebhookStore`; `store.insertEvent(UUID.randomUUID(), null, "conciliation.report_open", "{}", Instant.now())` fans out to the operator namespace (Task 2/3 machinery). Three events → three PENDING deliveries.

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
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class OperatorWebhookDeliveriesRestApiTest extends IntegrationTestBase {

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

  private String registerOperatorEndpoint() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://ops.example/deliveries\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.endpointId");
  }

  private void seedEvents(int count) {
    for (int i = 0; i < count; i++) {
      webhookStore.insertEvent(UUID.randomUUID(), null, "conciliation.report_open",
          "{\"probe\":" + i + "}", Instant.now());
    }
  }

  @Test
  void deliveriesPaginateWithNextCursor() throws Exception {
    String endpointId = registerOperatorEndpoint();
    seedEvents(3);
    MvcResult page1 = mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", operatorAuth()).param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(header().exists("Next-Cursor"))
        .andReturn();
    String cursor = page1.getResponse().getHeader("Next-Cursor");
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", operatorAuth()).param("limit", "2").param("after", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void redriveRequeuesAFailedDelivery() throws Exception {
    String endpointId = registerOperatorEndpoint();
    seedEvents(1);
    MvcResult listed = mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", operatorAuth()))
        .andExpect(status().isOk()).andReturn();
    String deliveryId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].deliveryId");
    // Force terminal FAILED so redrive has something to requeue.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update webhooks.webhook_delivery set status = 'FAILED', attempts = 8 "
          + "where public_id = '" + deliveryId + "'");
    }
    mockMvc.perform(post("/v1/operator/webhook-deliveries/" + deliveryId + "/redrive")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + endpointId + "/deliveries")
            .header("Authorization", operatorAuth()).param("status", "PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].deliveryId").value(deliveryId))
        .andExpect(jsonPath("$[0].attempts").value(0));
  }

  @Test
  void operatorRoutesNeverTouchMerchantDeliveries() throws Exception {
    // A merchant endpoint + delivery...
    MvcResult merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Parity Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String bearer = com.jayway.jsonpath.JsonPath.read(
        merchant.getResponse().getContentAsString(), "$.apiKey.secret");
    MvcResult endpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://" + UUID.randomUUID() + ".example/m-hook\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantEndpointId = com.jayway.jsonpath.JsonPath.read(
        endpoint.getResponse().getContentAsString(), "$.endpointId");
    // ...is invisible on the operator surface: listing 404s, redrive of any
    // delivery under it is unreachable because the listing never yields ids.
    mockMvc.perform(get("/v1/operator/webhook-endpoints/" + merchantEndpointId + "/deliveries")
            .header("Authorization", operatorAuth()))
        .andExpect(status().isNotFound());
  }
}
```

(If `DeliveryResponse` names the id differently than `deliveryId`, mirror the merchant deliveries suite's assertions.)

- [ ] **Step 2: Remote RED** — push; FAIL (404s on the operator delivery routes).

- [ ] **Step 3: Implement**

`JdbcClientWebhookStore.java` — in `listDeliveries` and `requeueFailedDelivery`, change the merchant join/filter predicates from `= :merchantPublicId` to `is not distinct from :merchantPublicId` (same `-- null audience = the operator namespace` comment).

`OperatorWebhookDeliveriesController.java` — mirror `WebhookDeliveriesController` exactly (same pagination logic, same limit validation, `@Idempotent` redrive), operator-authenticated, scoping by `null`:

```java
package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookDeliveryException;
import com.leandrossb.nummus.webhooks.interfaces.dto.DeliveryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorWebhookDeliveriesController {

  private final WebhookEndpointsService endpoints;
  private final WebhookStore store;

  OperatorWebhookDeliveriesController(WebhookEndpointsService endpoints, WebhookStore store) {
    this.endpoints = endpoints;
    this.store = store;
  }

  @GetMapping("/v1/operator/webhook-endpoints/{id}/deliveries")
  ResponseEntity<List<DeliveryResponse>> deliveries(AuthenticatedOperator operator,
      @PathVariable UUID id, @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    endpoints.get(null, id); // 404 for unknown, deleted, or merchant endpoints
    var page = store.listDeliveries(null, id, status, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(DeliveryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).deliveryPublicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(DeliveryResponse::from).toList());
  }

  @Idempotent
  @PostMapping("/v1/operator/webhook-deliveries/{id}/redrive")
  ResponseEntity<Void> redrive(AuthenticatedOperator operator, @PathVariable UUID id) {
    if (!store.requeueFailedDelivery(null, id)) {
      throw new UnknownWebhookDeliveryException(id);
    }
    return ResponseEntity.accepted().build();
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='OperatorWebhookDeliveriesRestApiTest,WebhookDeliveriesPaginationTest'` → all green; full `verify` → BUILD SUCCESS, **307 tests** (304 + 3).

- [ ] **Step 5: Commit** — `feat: give operator deliveries listing and redrive parity` + trailer; push.

---

### Task 5: The `conciliation.report_open` digest (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationAlerts.java` (port)
- Create: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxConciliationAlerts.java` (adapter)
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java` (publish on OPEN)
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationAlertsTest.java`

**Interfaces:**
- Consumes: Task 2's `insertEvent(eventId, null, ...)` operator fan-out; Task 3's operator endpoints + `ConciliationEventTypes.REPORT_OPEN`.
- Produces: `ConciliationAlerts.reportOpen(UUID reportPublicId, Instant from, Instant to, int matched, int amountMismatched, int missingInternal, int missingExternal)` — called inside the ingest transaction, only when the summary lands OPEN (manual AND scheduled paths share it; the Task 6 worker needs no publishing logic of its own). Wire envelope: `{id, type, occurredAt, data:{reportId, from, to, matched, amountMismatched, missingInternal, missingExternal}}`.

**Divergence recipe (used by this task's tests):** settle a payment end-to-end (merchant → account → intent → simulator pay → GET settles), then delete the simulator charge row via `adminConnection` — the internal settlement exists, the external line is gone, so the next ingest over a window covering it lands `MISSING_EXTERNAL` → OPEN. (If `ConciliationRestApiTest` already has a divergence recipe, follow that instead.)

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationAlertsTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create(null).secret();
  }

  private void registerOperatorEndpoint(String typesJson) throws Exception {
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://ops.example/alerts\",\"eventTypes\":" + typesJson + "}"))
        .andExpect(status().isCreated());
  }

  private String settleAndHideExternalCharge() throws Exception {
    MvcResult merchant = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alert Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String bearer = com.jayway.jsonpath.JsonPath.read(
        merchant.getResponse().getContentAsString(), "$.apiKey.secret");
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Alert Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    String accountLocation = opened.getResponse().getHeader("Location");
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intent = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":7.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intent.getResponse().getHeader("Location");
    MvcResult fetched = mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk()).andReturn();
    String chargeId = com.jayway.jsonpath.JsonPath.read(
        fetched.getResponse().getContentAsString(), "$.chargeId");
    mockMvc.perform(post("/simulator/charges/" + chargeId + "/pay")).andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk());
    // Hide the external evidence: the settlement is internal-only now.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("delete from psp_simulator.charge where public_id = '" + chargeId + "'");
    }
    return bearer;
  }

  private int reportOpenEventCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_event "
            + "where type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int pendingOperatorDeliveries() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_delivery d "
            + "join webhooks.webhook_endpoint p on p.id = d.endpoint_id "
            + "join webhooks.webhook_event e on e.id = d.event_id "
            + "where p.merchant_public_id is null and e.type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void openIngestPushesOneDigestPerSubscribedOperatorEndpoint() throws Exception {
    registerOperatorEndpoint("[\"conciliation.report_open\"]");
    registerOperatorEndpoint("[]"); // all types — also subscribed
    settleAndHideExternalCharge();

    String from = Instant.now().minus(1, ChronoUnit.HOURS).toString();
    String to = Instant.now().plus(1, ChronoUnit.HOURS).toString();
    MvcResult report = mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    Assertions.assertEquals("OPEN", com.jayway.jsonpath.JsonPath.read(
        report.getResponse().getContentAsString(), "$.status"));

    Assertions.assertEquals(1, reportOpenEventCount());
    Assertions.assertEquals(2, pendingOperatorDeliveries());
  }

  @Test
  void conciledIngestEmitsNothing() throws Exception {
    // A clean settle (external evidence intact) ingests CONCILED: no event.
    settleAndHideExternalCharge();
    // recreate the external evidence: settle a SECOND, fully visible payment
    // so the window has matching lines on both sides.
    // (simplest: run the same flow but skip the delete)
    // -- instead of a second flow, just undo the hiding for this test:
    // see note below: this test uses its own setup.
    Assertions.fail("placeholder - implementer: see note");
  }

  @Test
  void rejectedIngestEmitsNothing() throws Exception {
    int before = reportOpenEventCount();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + Instant.now() + "\",\"to\":\"" + Instant.now().minusSeconds(60) + "\"}"))
        .andExpect(status().isBadRequest());
    Assertions.assertEquals(before, reportOpenEventCount());
  }
}
```

**Note for the implementer on test 2 (`conciledIngestEmitsNothing`):** the plan's placeholder above is deliberate guidance, not shipped code. Implement it as: full clean settle (same helper flow MINUS the simulator-charge delete — extract `settlePayment()` and make the delete a separate step the first test applies), ingest a window covering it, assert `CONCILED` and `reportOpenEventCount()` unchanged. Do NOT keep any `Assertions.fail` placeholder in the committed test.

- [ ] **Step 2: Remote RED** — push; FAIL (no `conciliation.report_open` events ever: count assertions fail; `ConciliationAlerts` type absent is fine — the test only observes behavior).

- [ ] **Step 3: Implement**

`ConciliationAlerts.java`:

```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.UUID;

/** Push-side port for conciliation alerts. Implemented by the webhook outbox;
 *  called inside the ingest transaction, so an alert commits with the report
 *  or not at all. */
public interface ConciliationAlerts {

  /** Fired exactly once per report that lands OPEN. */
  void reportOpen(UUID reportPublicId, Instant from, Instant to, int matched,
      int amountMismatched, int missingInternal, int missingExternal);
}
```

`OutboxConciliationAlerts.java` (webhooks side; mirrors `OutboxIntentLifecycleEvents`):

```java
package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.conciliation.application.ConciliationAlerts;
import com.leandrossb.nummus.conciliation.application.ConciliationEventTypes;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Writes the report-open digest into the outbox inside the ingest
 *  transaction ({@code MANDATORY} fails fast without one). Audience is NULL —
 *  the operator namespace — so fan-out reaches operator endpoints only. */
@Component
public class OutboxConciliationAlerts implements ConciliationAlerts {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxConciliationAlerts(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void reportOpen(UUID reportPublicId, Instant from, Instant to, int matched,
      int amountMismatched, int missingInternal, int missingExternal) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(eventId,
        ConciliationEventTypes.REPORT_OPEN, Instant.now(),
        new Data(reportPublicId, from, to, matched, amountMismatched, missingInternal,
            missingExternal)));
    store.insertEvent(eventId, null, ConciliationEventTypes.REPORT_OPEN, payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  record Data(UUID reportId, Instant from, Instant to, int matched, int amountMismatched,
      int missingInternal, int missingExternal) {
  }
}
```

`ConciliationService.java` — constructor gains `ConciliationAlerts alerts`; at the end of `ingest`, after `store.insert(summary, outcome.lines())`:

```java
    if (!summary.conciled()) {
      alerts.reportOpen(summary.publicId(), from, to, summary.matched(),
          summary.amountMismatched(), summary.missingInternal(), summary.missingExternal());
    }
    return summary;
```

(Adapt accessor names to the actual `SettlementReportSummary` record — it already carries `status`/counts from the M6 ingest code; "conciled" is whatever check the record supports, e.g. `"CONCILED".equals(summary.status())`.)

Ripples: none expected — the Spring context wires the new adapter automatically; `ConciliationRestApiTest` and friends keep passing (CONCILED windows publish nothing, and no operator endpoints exist in those suites).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ConciliationAlertsTest,ConciliationRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **310 tests** (307 + 3).

- [ ] **Step 5: Commit** — `feat: push a report-open digest to operator webhooks` + trailer; push.

---

### Task 6: The scheduler — tumbling windows with self-healing state (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationProperties.java`
- Create: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationWorker.java`
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationStore.java` (three state methods)
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/infrastructure/JdbcClientConciliationStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java` (`ingestIfAnyLines`)
- Modify: `src/test/java/com/leandrossb/nummus/testutils/IntegrationTestBase.java` (two delay pins)
- Test: `src/test/java/com/leandrossb/nummus/conciliation/ConciliationWorkerTest.java`

**Interfaces:**
- Consumes: Task 1's `ingest_state`; Task 5's in-transaction publishing.
- Produces: `ConciliationStore.selfHealingWindowStart()` (`greatest(state, max(period_to))`), `currentWindowEnd(Duration lag)` (DB clock minus lag), `advanceWindowEnd(Instant end)`; `ConciliationService.ingestIfAnyLines(Instant from, Instant to)` returning `Optional<SettlementReportSummary>` — empty when both sides had zero lines (nothing persisted); `ConciliationWorker.tick()` (`@Scheduled` shell, warn-and-swallow) delegating to `@Transactional runWindow()`; properties `nummus.conciliation.poll-delay-ms` (300000), `initial-delay-ms` (60000), `window-lag` (PT30S).

- [ ] **Step 1: Write the failing test** (worker bean invoked directly; delays pinned so no scheduler fires)

Add to `IntegrationTestBase`'s `@DynamicPropertySource` method:

```java
    registry.add("nummus.conciliation.poll-delay-ms", () -> "3600000");
    registry.add("nummus.conciliation.initial-delay-ms", () -> "3600000");
```

```java
package com.leandrossb.nummus.conciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.conciliation.application.ConciliationWorker;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ConciliationWorkerTest extends IntegrationTestBase {

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

  private Instant dbNow() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select now()")) {
      rs.next();
      return rs.getTimestamp(1).toInstant();
    }
  }

  private Instant lastWindowEnd() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_window_end from conciliation.ingest_state "
            + "where id = 1")) {
      rs.next();
      return rs.getTimestamp(1).toInstant();
    }
  }

  private int reportCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from conciliation.settlement_report")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void emptyWindowWritesNoReportAndAdvancesTheMarker() throws Exception {
    Instant before = lastWindowEnd();
    int reports = reportCount();
    worker.tick();
    Assertions.assertEquals(reports, reportCount(), "quiet window must not spam reports");
    Instant after = lastWindowEnd();
    Assertions.assertTrue(after.isAfter(before), "marker advances even on an empty window");
  }

  @Test
  void openWindowWritesReportEventAndAdvanceTogether() throws Exception {
    // One operator endpoint to observe the digest.
    mockMvc.perform(post("/v1/operator/webhook-endpoints")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://ops.example/worker\",\"eventTypes\":[\"conciliation.report_open\"]}"))
        .andExpect(status().isCreated());
    // A divergence in the live window: settle internally, hide externally
    // (same recipe as ConciliationAlertsTest — extract or repeat the helper).
    settleAndHideExternalCharge();

    int eventsBefore = countReportOpenEvents();
    int reports = reportCount();
    worker.tick();

    Assertions.assertEquals(reports + 1, reportCount(), "the divergence must be ingested");
    Assertions.assertEquals(eventsBefore + 1, countReportOpenEvents());
    // The window end honors the lag: the new marker sits at least ~29s behind now.
    Assertions.assertTrue(dbNow().isAfter(lastWindowEnd().plusSeconds(29)),
        "window end must hold back the lag");
  }

  @Test
  void manualOverlapThenTickStartsAfterTheManualReport() throws Exception {
    settleAndHideExternalCharge();
    // Manual wide ingest covering everything up to now.
    String from = Instant.now().minusSeconds(3600).toString();
    String to = Instant.now().toString();
    mockMvc.perform(post("/v1/conciliation/reports")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"))
        .andExpect(status().isCreated());
    int manualReports = reportCount();

    worker.tick();

    // The manual report covered pending territory; the scheduler must start
    // after its period_to, ingest nothing new, and write no second report
    // over the same ground.
    Assertions.assertTrue(reportCount() <= manualReports + 1,
        "no re-coverage of the manual window");
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) as n from conciliation.settlement_report r "
            + "join conciliation.report_line l on l.report_id = r.id "
            + "group by l.charge_public_id having count(*) > 1")) {
      Assertions.assertFalse(rs.next(), "no charge may appear in two reports");
    }
  }

  @Test
  void tickIsANoOpWhenTheMarkerIsAheadOfTheLaggedNow() throws Exception {
    // Push the marker into the future: end <= start must short-circuit.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update conciliation.ingest_state "
          + "set last_window_end = now() + interval '1 hour' where id = 1");
    }
    Instant pushed = lastWindowEnd();
    worker.tick();
    Assertions.assertEquals(pushed, lastWindowEnd());
  }

  private int countReportOpenEvents() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from webhooks.webhook_event "
            + "where type = 'conciliation.report_open'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private void settleAndHideExternalCharge() throws Exception {
    // Same recipe as ConciliationAlertsTest: merchant -> account -> intent ->
    // simulator pay -> GET settles -> delete the simulator charge row.
    // Implement per the ConciliationAlertsTest helper (this suite repeats it).
    throw new UnsupportedOperationException("implement per ConciliationAlertsTest's helper");
  }
}
```

**Implementer note:** `settleAndHideExternalCharge` must be the real helper (copy from `ConciliationAlertsTest`), not the `UnsupportedOperationException` stub — the stub exists to keep this plan block short; the committed test must not contain it. Consider extracting the shared flow into a package-private test fixture class used by both suites if that reads cleaner — house judgment call.

- [ ] **Step 2: Remote RED** — push; compilation FAIL (`ConciliationWorker`/`ConciliationProperties` absent) — the expected RED.

- [ ] **Step 3: Implement**

`ConciliationProperties.java`:

```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Scheduled-ingest policy: tumbling windows every poll delay, the window
 *  end held back by the lag so the M6 clock-skew boundary never straddles a
 *  window edge (a straddle would otherwise leave a permanent spurious
 *  MISSING pair no re-ingest can heal). */
@ConfigurationProperties(prefix = "nummus.conciliation")
public record ConciliationProperties(
    @DefaultValue("300000") long pollDelayMs,
    @DefaultValue("60000") long initialDelayMs,
    @DefaultValue("PT30S") Duration windowLag) {
}
```

`ConciliationStore.java` — add:

```java
  /** greatest(ingest_state.last_window_end, max(settlement_report.period_to)):
   *  manual ingests that covered pending territory are never re-covered. */
  Instant selfHealingWindowStart();

  /** The DB clock minus the lag — the window end. */
  Instant currentWindowEnd(java.time.Duration lag);

  /** Advances the marker; called only with the window end that was ingested. */
  void advanceWindowEnd(Instant end);
```

`JdbcClientConciliationStore.java`:

```java
  @Override
  public Instant selfHealingWindowStart() {
    return jdbc.sql("""
        select greatest(s.last_window_end,
          coalesce((select max(r.period_to) from conciliation.settlement_report r), s.last_window_end))
        from conciliation.ingest_state s where s.id = 1
        """)
        .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).single();
  }

  @Override
  public Instant currentWindowEnd(Duration lag) {
    return jdbc.sql("select now() - make_interval(secs => :lagSeconds)")
        .param("lagSeconds", lag.toMillis() / 1000.0)
        .query((rs, i) -> rs.getObject(1, java.time.OffsetDateTime.class).toInstant()).single();
  }

  @Override
  public void advanceWindowEnd(Instant end) {
    jdbc.sql("update conciliation.ingest_state set last_window_end = :end, updated_at = now() "
        + "where id = 1")
        .param("end", java.time.OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC))
        .update();
  }
```

`ConciliationService.java` — refactor `ingest` to share the fetch-match-persist body and add the skip-empty variant (manual `ingest` keeps today's always-persist behavior):

```java
  @Transactional
  public SettlementReportSummary ingest(Instant from, Instant to) {
    return doIngest(from, to).summary();
  }

  /** Scheduled path: an empty window (zero lines both sides) persists nothing. */
  @Transactional
  public Optional<SettlementReportSummary> ingestIfAnyLines(Instant from, Instant to) {
    var outcome = doIngest(from, to);
    return outcome.empty() ? Optional.empty() : Optional.of(outcome.summary());
  }
```

(`doIngest` returns a small private record `(boolean empty, SettlementReportSummary summary)`; "empty" = the fetched report had no lines AND the internal list was empty — checked BEFORE any insert; keep the OPEN-publish hook at the end of the persisting path only.)

`ConciliationWorker.java`:

```java
package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled tumbling-window re-ingest: start self-heals past manual ingests,
 * the end holds back the lag, an empty window persists nothing but still
 * advances the marker, and report + digest + advance commit atomically.
 * A failed tick warns and retries the same window next time — the same
 * single-process fixedDelay discipline as the webhook workers.
 */
@Component
public class ConciliationWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConciliationWorker.class);

  private final ConciliationService conciliation;
  private final ConciliationStore store;
  private final ConciliationProperties properties;

  public ConciliationWorker(ConciliationService conciliation, ConciliationStore store,
      ConciliationProperties properties) {
    this.conciliation = conciliation;
    this.store = store;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.conciliation.poll-delay-ms:300000}",
      initialDelayString = "${nummus.conciliation.initial-delay-ms:60000}")
  public void tick() {
    try {
      runWindow();
    } catch (Exception e) {
      LOGGER.warn("conciliation ingest tick failed; the window will retry", e);
    }
  }

  @Transactional
  void runWindow() {
    Instant start = store.selfHealingWindowStart();
    Instant end = store.currentWindowEnd(properties.windowLag());
    if (!start.isBefore(end)) {
      return;
    }
    conciliation.ingestIfAnyLines(start, end);
    store.advanceWindowEnd(end);
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ConciliationWorkerTest,ConciliationAlertsTest,ConciliationRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **315 tests** (310 + 5). Watch the full run: the `IntegrationTestBase` pin addition rebuilds every cached context once — connection usage stays within the M11 ceiling.

- [ ] **Step 5: Commit** — `feat: schedule tumbling conciliation re-ingest` + trailer; push.

---

### Task 7: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M12 — Conciliation automation`; the **Conciliation** capability row extends: "…; re-ingest is scheduled over tumbling self-healing windows and every OPEN report pushes a digest to operator webhooks"; the **Webhooks** capability row extends: "…; a second namespace serves operator endpoints (conciliation alerts)".
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M12 design

M12 automated conciliation: scheduled re-ingest over tumbling windows whose
start self-heals past manual ingests and whose end holds back a 30s lag for
the M6 clock skew, operator webhook endpoints riding the NULL-merchant
namespace of the existing outbox, and a `conciliation.report_open` digest
per OPEN report. Recon also closed a latent M5-era leak: event fan-out was
unscoped, delivering one merchant's payment events to every merchant's
endpoints — fan-out now binds to the event's merchant (or NULL for
operators). Known bounds, deliberate:

- **Single-process scheduler and delivery worker** — scale-out needs the
  `SKIP LOCKED` treatment already documented for delivery and retention.
- **Digest-only alerting** — per-line divergence stays behind the report
  GET; no per-line push, no severity routing.
- **The operator endpoint set is role-level shared** — no per-operator
  ownership until audit attribution lands (the M8 bound carries).
- **The lag is a property, not an SLA** — 30s of alert latency buys skew
  safety; a real PSP adapter still owes the settlement-timestamp contract.
- **Empty scheduled windows advance silently** — a quiet system leaves no
  trace beyond the marker; observability of tick health is log-only.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 315, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M12 conciliation automation complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V15 nullable namespace + ingest state (+ schema/roles tests) | 1 |
| Audience-scoped fan-out + cross-tenant leak fix | 2 |
| Operator endpoints (register/list/delete, policy, type catalog, isolation) | 3 |
| Operator deliveries pagination + redrive parity | 4 |
| `conciliation.report_open` digest on any OPEN ingest (transactional) | 5 |
| Tumbling scheduler (self-healing start, lag, empty-window skip, atomicity, pins) | 6 |
| README + backlog + final gate (315) | 7 |
