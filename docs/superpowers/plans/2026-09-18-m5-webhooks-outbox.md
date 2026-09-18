# M5 Webhooks via Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Payment-intent lifecycle changes publish events to a transactional outbox (same transaction as the state change) and a scheduled worker delivers them at-least-once to registered HTTPS endpoints, HMAC-signed, with exponential backoff and a bounded retry budget.

**Architecture:** `payments.application` defines `IntentLifecycleEvents` (producer-owned port, the M3 inversion pattern); the `webhooks` module implements it, serializing the envelope once and inserting the event plus one delivery row per ACTIVE endpoint subscribed to the type — inside the caller's transaction. A `@Scheduled` worker claims due deliveries, POSTs the exact stored bytes via `RestClient` with `Nummus-Signature`/`Nummus-Event` headers, and records success/retry/permanent-failure. Endpoint subscriptions are REST-managed (`/v1/webhook-endpoints`, soft delete, secret returned once).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (web, jdbc, validation, flyway, aspectj), PostgreSQL via Testcontainers, JUnit 5 + MockMvc, JDK `com.sun.net.httpserver` as the test receiver, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-18-m5-webhooks-outbox-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`).
- **Every `./mvnw` invocation needs the Docker-tunnel env prefix** (`<env-prefix>` below; the tunnel is usually up — verify first):
  ```
  JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 DOCKER_HOST=unix:///tmp/megalan-docker.sock \
  TESTCONTAINERS_HOST_OVERRIDE=192.168.0.210 TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ```
  Tunnel check: `curl -s --max-time 3 --unix-socket /tmp/megalan-docker.sock http://localhost/_ping` → `OK`. If dead: `ssh -nNT -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L /tmp/megalan-docker.sock:/var/run/docker.sock megalan` (background). Running without the prefix fails with `Could not find a valid Docker environment` — environment error, not code.
- **Jackson 3 on Boot 4.1.1** (M4 lesson): `tools.jackson.databind.ObjectMapper`; its exceptions are unchecked (no catch). Jackson *annotations* stay at `com.fasterxml.jackson.annotation.*` (e.g. `@JsonInclude`).
- **DB clock runs ~5s behind the JVM** (M4 lesson): never write a JVM-derived expiry and assert DB-side expiry in the same breath; age rows DB-side (`UPDATE … SET next_attempt_at = now() - interval '1 second'`) like the M4 suite does.
- **Test classes end in `Test`.** Success criteria: `./mvnw verify` green at every commit; final count **163 tests** (137 today + 26 new: 2 schema/roles, 5 store, 2 signature, 3 publish, 4 worker, 3 delivery/E2E, 6 REST, 1 ArchUnit rule).
- **Bean ordering:** Task 4 lands the `IntentLifecycleEvents` port, its ONLY implementation (`OutboxIntentLifecycleEvents`), and the `PaymentsServiceImpl` wiring in ONE task — every `@SpringBootTest` context stays green at every commit.
- **ArchUnit** `persistenceTypesOnlyInInfrastructure`: JDBC/`java.sql` types only under `..infrastructure..` packages.
- **No placeholder code** — every class ships real.
- Money never travels as a JSON number: the envelope's `amount` is `toPlainString()`.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V8__webhooks_schema.sql               (Task 1)
src/main/java/com/leandrossb/nummus/webhooks/
  domain/EndpointStatus.java, WebhookEndpoint.java, UnknownWebhookEndpointException.java (Task 2)
  application/WebhookStore.java, DueDelivery.java, DeliveryRecord.java (Task 2)
  infrastructure/JdbcClientWebhookStore.java                          (Task 2)
  application/SignatureHeaders.java                                   (Task 3)
  application/EventDeliveryClient.java, DeliveryResult.java,
    WebhookProperties.java, WebhookDeliveryWorker.java                (Task 5)
  infrastructure/RestClientEventDeliveryClient.java                   (Task 6)
src/main/java/com/leandrossb/nummus/payments/application/
  IntentEventTypes.java, IntentLifecycleEvent.java,
  IntentLifecycleEvents.java                                          (Task 4)
src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java (Task 4: publish calls)
src/main/java/com/leandrossb/nummus/webhooks/infrastructure/
  OutboxIntentLifecycleEvents.java                                    (Task 4)
src/main/java/com/leandrossb/nummus/webhooks/application/
  WebhookEndpointsService.java                                        (Task 7)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/
  WebhookEndpointsController.java, WebhookDeliveriesController.java,
  dto/CreateEndpointRequest.java, CreateEndpointResponse.java,
    EndpointResponse.java, DeliveryResponse.java                      (Task 7)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (Task 7: 404 entry)
src/test/java/com/leandrossb/nummus/webhooks/
  WebhooksSchemaTest.java, WebhooksRolesTest.java                     (Task 1)
  WebhookStoreTest.java                                               (Task 2)
  SignatureHeadersTest.java                                           (Task 3)
  WebhookPublishTest.java                                             (Task 4)
  WebhookDeliveryWorkerTest.java                                      (Task 5)
  ReceiverServer.java, WebhookDeliveryClientTest.java                 (Task 6)
  WebhookEndpointsRestApiTest.java                                    (Task 7)
src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java (Task 8: webhooks rule)
README.md, docs/m2-backlog.md                                         (Task 9)
```

---

### Task 1: `V8__webhooks_schema.sql` + schema and roles tests

**Files:**
- Create: `src/main/resources/db/migration/V8__webhooks_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhooksSchemaTest.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhooksRolesTest.java`

**Interfaces:**
- Consumes: Flyway chain (V1–V7), `IntegrationTestBase` (container, `adminConnection()`, `appConnection()`, `APP_ROLE_PASSWORD`).
- Produces: schema `webhooks` with `webhook_endpoint` (identity pk, `public_id uuid unique default gen_random_uuid()`, `url text check (^https?://)`, `secret text`, `event_types jsonb not null default '[]'`, `status text check (ACTIVE|DELETED) default 'ACTIVE'`, `created_at`), `webhook_event` (identity pk, `public_id uuid unique`, `type text`, `payload text`, `occurred_at timestamptz`, `created_at`), `webhook_delivery` (identity pk, FK event + endpoint, `status text check (PENDING|SUCCEEDED|FAILED) default 'PENDING'`, `attempts int default 0`, `next_attempt_at timestamptz default now()`, `last_attempt_at timestamptz`, `last_response_status int`, `unique(event_id, endpoint_id)`), index `webhook_delivery_due_idx (status, next_attempt_at)`. Grants `nummus_app`: usage on schema + `select, insert, update` on all three tables (no delete — deletion is a status transition).

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.webhooks;

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

class WebhooksSchemaTest extends IntegrationTestBase {

  @Test
  void schemaEnforcesUrlCheckDeliveryUniquenessAndDefaults() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // Bad URL rejected by the check constraint.
      SQLException badUrl = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO webhooks.webhook_endpoint (url, secret) VALUES ('ftp://nope', 's')"));
      assertEquals("23514", badUrl.getSQLState());

      String endpointId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO webhooks.webhook_endpoint (public_id, url, secret) VALUES ('"
          + endpointId + "', 'https://merchant.example/hook', 'whsec_x')");
      try (ResultSet rs = st.executeQuery(
          "SELECT event_types::text, status FROM webhooks.webhook_endpoint WHERE public_id = '" + endpointId + "'")) {
        assertTrue(rs.next());
        assertEquals("[]", rs.getString(1).trim());
        assertEquals("ACTIVE", rs.getString(2));
      }

      String eventId = UUID.randomUUID().toString();
      st.executeUpdate("INSERT INTO webhooks.webhook_event (public_id, type, payload, occurred_at) VALUES ('"
          + eventId + "', 'payment_intent.settled', '{}', now())");
      long deliveryId = 0;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM webhooks.webhook_event WHERE public_id = '" + eventId + "'")) {
        rs.next();
        deliveryId = rs.getLong(1);
      }
      long endpointRowId = 0;
      try (ResultSet rs = st.executeQuery(
          "SELECT id FROM webhooks.webhook_endpoint WHERE public_id = '" + endpointId + "'")) {
        rs.next();
        endpointRowId = rs.getLong(1);
      }
      st.executeUpdate("INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id) VALUES ("
          + deliveryId + ", " + endpointRowId + ")");
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id) VALUES ("
              + deliveryId + ", " + endpointRowId + ")"));
      assertEquals("23505", duplicate.getSQLState());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhooksSchemaTest`
Expected: FAIL — `schema "webhooks" does not exist`.

- [ ] **Step 3: Write the migration** (spec's SQL verbatim)

```sql
-- M5 webhooks: transactional outbox for merchant event delivery. Events and
-- their per-endpoint delivery rows are written in the SAME transaction as the
-- state change that produced them (fan-out at write time — subscription
-- semantics are exact). Delivery is at-least-once with bounded retries.
-- Endpoints soft-delete (status DELETED) so delivery history survives; hence
-- no delete grant.

create schema webhooks;

create table webhooks.webhook_endpoint (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  url         text not null check (url ~ '^https?://'),
  secret      text not null,
  event_types jsonb not null default '[]'::jsonb,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','DELETED')),
  created_at  timestamptz not null default now()
);

create table webhooks.webhook_event (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  type        text not null,
  payload     text not null,
  occurred_at timestamptz not null,
  created_at  timestamptz not null default now()
);

create table webhooks.webhook_delivery (
  id                   bigint generated always as identity primary key,
  event_id             bigint not null references webhooks.webhook_event(id),
  endpoint_id          bigint not null references webhooks.webhook_endpoint(id),
  status               text not null default 'PENDING'
                       check (status in ('PENDING','SUCCEEDED','FAILED')),
  attempts             int not null default 0,
  next_attempt_at      timestamptz not null default now(),
  last_attempt_at      timestamptz,
  last_response_status int,
  unique (event_id, endpoint_id)
);

create index webhook_delivery_due_idx
  on webhooks.webhook_delivery (status, next_attempt_at);

grant usage on schema webhooks to nummus_app;
grant select, insert, update on webhooks.webhook_endpoint,
  webhooks.webhook_event, webhooks.webhook_delivery to nummus_app;
```

- [ ] **Step 4: Write the roles test** (M4 lesson — grants are exercised, not assumed)

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class WebhooksRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleCanRunTheFullDeliveryLifecycle() throws Exception {
    String key = "roles-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO webhooks.webhook_endpoint (public_id, url, secret) VALUES ('"
          + UUID.randomUUID() + "', 'https://merchant.example/" + key + "', 'whsec_role')");
      st.executeUpdate("INSERT INTO webhooks.webhook_event (public_id, type, payload, occurred_at) VALUES ('"
          + UUID.randomUUID() + "', 'payment_intent.settled', '{}', now())");
      st.executeUpdate("""
          INSERT INTO webhooks.webhook_delivery (event_id, endpoint_id)
          SELECT e.id, p.id FROM webhooks.webhook_event e, webhooks.webhook_endpoint p
          WHERE p.url LIKE '%/""" + key + "'");
      st.executeUpdate("""
          UPDATE webhooks.webhook_delivery SET status = 'SUCCEEDED', attempts = 1,
            last_attempt_at = now(), last_response_status = 200
          WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/""" + key + "')");
      try (ResultSet rs = st.executeQuery("""
          SELECT d.status, d.attempts, d.last_response_status FROM webhooks.webhook_delivery d
          WHERE d.endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/""" + key + "')")) {
        assertTrue(rs.next());
        assertEquals("SUCCEEDED", rs.getString(1));
        assertEquals(1, rs.getInt(2));
        assertEquals(200, rs.getInt(3));
      }
    }
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `<env-prefix> ./mvnw test -Dtest='WebhooksSchemaTest,WebhooksRolesTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V8__webhooks_schema.sql \
  src/test/java/com/leandrossb/nummus/webhooks/
git commit -m "feat: add the webhooks outbox schema (V8)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: `webhooks` domain, `WebhookStore` port, JDBC adapter (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/domain/EndpointStatus.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/domain/WebhookEndpoint.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/domain/UnknownWebhookEndpointException.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/DueDelivery.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/DeliveryRecord.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookStoreTest.java`

**Interfaces:**
- Consumes: V8 tables (Task 1), `IntegrationTestBase`.
- Produces (Tasks 4–7 consume):
  - `enum EndpointStatus { ACTIVE, DELETED }`
  - `record WebhookEndpoint(UUID publicId, URI url, String secret, List<String> eventTypes, EndpointStatus status, Instant createdAt)`
  - `record DueDelivery(long id, EndpointStatus endpointStatus, URI url, String secret, String eventType, String payload, int attempts)`
  - `record DeliveryRecord(long id, UUID eventPublicId, String eventType, String status, int attempts, Integer lastResponseStatus, Instant nextAttemptAt)`
  - `interface WebhookStore { WebhookEndpoint insertEndpoint(WebhookEndpoint e); List<WebhookEndpoint> listActiveEndpoints(); Optional<WebhookEndpoint> findActiveEndpoint(UUID publicId); boolean markEndpointDeleted(UUID publicId); void insertEvent(UUID eventPublicId, String type, String payload, Instant occurredAt); List<DueDelivery> claimDueDeliveries(Instant now, int limit); void recordDeliverySuccess(long deliveryId, Integer responseStatus); void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt); void recordDeliveryFailure(long deliveryId, Integer responseStatus); List<DeliveryRecord> listDeliveries(UUID endpointPublicId, String status, int limit); }`
  - Fan-out semantics of `insertEvent`: INSERT the event, then `INSERT INTO webhook_delivery (event_id, endpoint_id) SELECT <event>, id FROM webhook_endpoint WHERE status = 'ACTIVE' AND (jsonb_array_length(event_types) = 0 OR event_types @> to_jsonb(<type>))`.
  - Outcome methods are guarded (`WHERE id = :id AND status = 'PENDING'`); retry/failure increment `attempts` and stamp `last_attempt_at = now()` (DB clock — avoids the JVM/DB skew for attempt bookkeeping).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import com.leandrossb.nummus.webhooks.application.DueDelivery;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookStoreTest extends IntegrationTestBase {

  @Autowired
  private WebhookStore store;

  private WebhookEndpoint endpoint(String path, List<String> types) {
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(),
        URI.create("https://merchant.example/" + path), "whsec_" + path,
        types, EndpointStatus.ACTIVE, Instant.now()));
  }

  private void publish(String type) {
    store.insertEvent(UUID.randomUUID(), type, "{\"type\":\"" + type + "\"}", Instant.now());
  }

  @Test
  void endpointRoundTripAndSoftDelete() {
    var created = endpoint("roundtrip", List.of("payment_intent.settled"));
    assertEquals(EndpointStatus.ACTIVE, created.status());
    assertTrue(store.listActiveEndpoints().stream()
        .anyMatch(e -> e.publicId().equals(created.publicId())));
    assertTrue(store.findActiveEndpoint(created.publicId()).isPresent());

    assertTrue(store.markEndpointDeleted(created.publicId()));
    assertTrue(store.findActiveEndpoint(created.publicId()).isEmpty());
    assertTrue(store.listActiveEndpoints().stream()
        .noneMatch(e -> e.publicId().equals(created.publicId())));
    assertTrue(!store.markEndpointDeleted(created.publicId()));
  }

  @Test
  void insertEventFansOutToActiveSubscribersOfTheTypeOnly() {
    var allTypes = endpoint("all", List.of());
    var settledOnly = endpoint("settled", List.of("payment_intent.settled"));
    var deleted = endpoint("gone", List.of());
    store.markEndpointDeleted(deleted.publicId());

    publish("payment_intent.settled");
    publish("payment_intent.failed");

    var settledDeliveries = store.listDeliveries(allTypes.publicId(), null, 50);
    assertEquals(1, store.listDeliveries(settledOnly.publicId(), null, 50).size());
    assertEquals(2, settledDeliveries.size()); // [] subscribes to every type
    assertEquals(0, store.listDeliveries(deleted.publicId(), null, 50).size());
  }

  @Test
  void claimDueDeliveriesRespectsDueTimeOrderAndLimit() throws Exception {
    var target = endpoint("claim", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());

    // Age one delivery DB-side so it is due now; the fresh one (next_attempt_at = now())
    // is borderline — push it out to keep the assertion deterministic.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() + interval '1 hour' "
          + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/claim')");
      // A second endpoint/event pair aged to be due:
    }
    var other = endpoint("claim-other", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.failed", "{}", Instant.now());
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() - interval '1 second' "
          + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/claim-other')");
    }

    List<DueDelivery> due = store.claimDueDeliveries(Instant.now(), 10);
    assertTrue(due.stream().anyMatch(d -> d.url().toString().endsWith("/claim-other")));
    assertTrue(due.stream().noneMatch(d -> d.url().toString().endsWith("/claim")));
    assertTrue(due.stream().allMatch(d -> d.payload() != null && d.eventType() != null));
  }

  @Test
  void outcomeRecordingMovesStatusAttemptsAndBackoff() {
    var target = endpoint("outcomes", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
    long deliveryId = store.claimDueDeliveries(Instant.now(), 10).stream()
        .filter(d -> d.url().toString().endsWith("/outcomes")).findFirst().orElseThrow().id();

    store.recordDeliveryRetry(deliveryId, 500, Instant.now().plusSeconds(30));
    Optional<DeliveryRecord> retried = store.listDeliveries(target.publicId(), null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst();
    assertTrue(retried.isPresent());
    assertEquals("PENDING", retried.get().status());
    assertEquals(1, retried.get().attempts());
    assertEquals(500, retried.get().lastResponseStatus());

    store.recordDeliverySuccess(deliveryId, 200);
    assertEquals("SUCCEEDED", store.listDeliveries(target.publicId(), null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst().orElseThrow().status());

    // Guarded: a second outcome on a non-PENDING row is a no-op (rowcount 0, no exception).
    store.recordDeliveryRetry(deliveryId, 500, Instant.now());
    assertEquals("SUCCEEDED", store.listDeliveries(target.publicId(), null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst().orElseThrow().status());
  }

  @Test
  void listDeliveriesFiltersByEndpointAndStatus() {
    var a = endpoint("filter-a", List.of());
    var b = endpoint("filter-b", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
    store.insertEvent(UUID.randomUUID(), "payment_intent.failed", "{}", Instant.now());

    store.claimDueDeliveries(Instant.now(), 10).stream()
        .filter(d -> d.url().toString().endsWith("/filter-a")).findFirst()
        .ifPresent(d -> store.recordDeliverySuccess(d.id()));

    assertEquals(1, store.listDeliveries(a.publicId(), "SUCCEEDED", 50).size());
    assertEquals(0, store.listDeliveries(a.publicId(), "FAILED", 50).size());
    assertEquals(2, store.listDeliveries(b.publicId(), null, 50).size());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookStoreTest`
Expected: compilation FAIL — the domain/application types do not exist.

- [ ] **Step 3: Write the types, port, and adapter**

`domain/EndpointStatus.java`:
```java
package com.leandrossb.nummus.webhooks.domain;

/** Endpoints soft-delete so delivery history survives unsubscription. */
public enum EndpointStatus {
  ACTIVE, DELETED
}
```

`domain/WebhookEndpoint.java`:
```java
package com.leandrossb.nummus.webhooks.domain;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/** A merchant-registered delivery target. {@code eventTypes} empty means all types. */
public record WebhookEndpoint(
    UUID publicId, URI url, String secret, List<String> eventTypes,
    EndpointStatus status, Instant createdAt) {
}
```

`domain/UnknownWebhookEndpointException.java`:
```java
package com.leandrossb.nummus.webhooks.domain;

import java.util.UUID;

/** Raised for unknown AND soft-deleted endpoints — both are gone to callers. */
public class UnknownWebhookEndpointException extends RuntimeException {

  public UnknownWebhookEndpointException(UUID publicId) {
    super("webhook endpoint not found: " + publicId);
  }
}
```

`application/DueDelivery.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import java.net.URI;

/** A delivery the worker may attempt now: target endpoint state plus the exact stored payload. */
public record DueDelivery(
    long id, EndpointStatus endpointStatus, URI url, String secret,
    String eventType, String payload, int attempts) {
}
```

`application/DeliveryRecord.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import java.time.Instant;
import java.util.UUID;

/** Read model for the deliveries listing. */
public record DeliveryRecord(
    long id, UUID eventPublicId, String eventType, String status,
    int attempts, Integer lastResponseStatus, Instant nextAttemptAt) {
}
```

`application/WebhookStore.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the outbox. {@link #insertEvent} fans out inside the
 * caller's transaction: one delivery row per ACTIVE endpoint whose
 * event_types is empty (all types) or contains the event's type. Outcome
 * methods are guarded on status = 'PENDING' and stamp last_attempt_at with
 * the database clock.
 */
public interface WebhookStore {

  WebhookEndpoint insertEndpoint(WebhookEndpoint endpoint);

  List<WebhookEndpoint> listActiveEndpoints();

  Optional<WebhookEndpoint> findActiveEndpoint(UUID publicId);

  boolean markEndpointDeleted(UUID publicId);

  void insertEvent(UUID eventPublicId, String type, String payload, Instant occurredAt);

  List<DueDelivery> claimDueDeliveries(Instant now, int limit);

  void recordDeliverySuccess(long deliveryId, Integer responseStatus);

  void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt);

  void recordDeliveryFailure(long deliveryId, Integer responseStatus);

  List<DeliveryRecord> listDeliveries(UUID endpointPublicId, String status, int limit);
}
```

`infrastructure/JdbcClientWebhookStore.java`:
```java
package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import com.leandrossb.nummus.webhooks.application.DueDelivery;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientWebhookStore implements WebhookStore {

  private final JdbcClient jdbc;

  public JdbcClientWebhookStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public WebhookEndpoint insertEndpoint(WebhookEndpoint endpoint) {
    jdbc.sql("""
        insert into webhooks.webhook_endpoint
          (public_id, url, secret, event_types, status, created_at)
        values (:publicId, :url, :secret, :eventTypes::jsonb, :status, :createdAt)
        """)
        .param("publicId", endpoint.publicId())
        .param("url", endpoint.url().toString())
        .param("secret", endpoint.secret())
        .param("eventTypes", typesJson(endpoint.eventTypes()))
        .param("status", endpoint.status().name())
        .param("createdAt", toOffsetDateTime(endpoint.createdAt()))
        .update();
    return endpoint;
  }

  @Override
  public List<WebhookEndpoint> listActiveEndpoints() {
    return jdbc.sql("""
        select public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint where status = 'ACTIVE' order by created_at, id
        """)
        .query((rs, i) -> mapEndpoint(rs)).list();
  }

  @Override
  public Optional<WebhookEndpoint> findActiveEndpoint(UUID publicId) {
    return jdbc.sql("""
        select public_id, url, secret, event_types::text, status, created_at
        from webhooks.webhook_endpoint where public_id = :publicId and status = 'ACTIVE'
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapEndpoint(rs)).optional();
  }

  @Override
  public boolean markEndpointDeleted(UUID publicId) {
    return jdbc.sql("""
        update webhooks.webhook_endpoint set status = 'DELETED'
        where public_id = :publicId and status = 'ACTIVE'
        """)
        .param("publicId", publicId).update() == 1;
  }

  @Override
  public void insertEvent(UUID eventPublicId, String type, String payload, Instant occurredAt) {
    jdbc.sql("""
        insert into webhooks.webhook_event (public_id, type, payload, occurred_at)
        values (:publicId, :type, :payload, :occurredAt)
        """)
        .param("publicId", eventPublicId)
        .param("type", type)
        .param("payload", payload)
        .param("occurredAt", toOffsetDateTime(occurredAt))
        .update();
    // Write-time fan-out: subscription semantics are exact at the event instant.
    jdbc.sql("""
        insert into webhooks.webhook_delivery (event_id, endpoint_id)
        select e.id, p.id
        from webhooks.webhook_event e
        cross join webhooks.webhook_endpoint p
        where e.public_id = :eventPublicId
          and p.status = 'ACTIVE'
          and (jsonb_array_length(p.event_types) = 0 or p.event_types @> to_jsonb(:type))
        """)
        .param("eventPublicId", eventPublicId)
        .param("type", type)
        .update();
  }

  @Override
  public List<DueDelivery> claimDueDeliveries(Instant now, int limit) {
    return jdbc.sql("""
        select d.id, p.status as endpoint_status, p.url, p.secret,
               e.type as event_type, e.payload, d.attempts
        from webhooks.webhook_delivery d
        join webhooks.webhook_event e on e.id = d.event_id
        join webhooks.webhook_endpoint p on p.id = d.endpoint_id
        where d.status = 'PENDING' and d.next_attempt_at <= :now
        order by d.id
        limit :limit
        """)
        .param("now", toOffsetDateTime(now))
        .param("limit", limit)
        .query((rs, i) -> new DueDelivery(rs.getLong(1),
            EndpointStatus.valueOf(rs.getString(2)), URI.create(rs.getString(3)),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)))
        .list();
  }

  @Override
  public void recordDeliverySuccess(long deliveryId, Integer responseStatus) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set status = 'SUCCEEDED', last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set attempts = attempts + 1, next_attempt_at = :nextAttemptAt,
            last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("nextAttemptAt", toOffsetDateTime(nextAttemptAt))
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public void recordDeliveryFailure(long deliveryId, Integer responseStatus) {
    jdbc.sql("""
        update webhooks.webhook_delivery
        set status = 'FAILED', attempts = attempts + 1,
            last_attempt_at = now(), last_response_status = :responseStatus
        where id = :id and status = 'PENDING'
        """)
        .param("responseStatus", responseStatus)
        .param("id", deliveryId).update();
  }

  @Override
  public List<DeliveryRecord> listDeliveries(UUID endpointPublicId, String status, int limit) {
    return jdbc.sql("""
        select d.id, e.public_id, e.type, d.status, d.attempts,
               d.last_response_status, d.next_attempt_at
        from webhooks.webhook_delivery d
        join webhooks.webhook_event e on e.id = d.event_id
        join webhooks.webhook_endpoint p on p.id = d.endpoint_id
        where p.public_id = :endpointPublicId
          and (:status is null or d.status = :status)
        order by d.id desc
        limit :limit
        """)
        .param("endpointPublicId", endpointPublicId)
        .param("status", status)
        .param("limit", limit)
        .query((rs, i) -> new DeliveryRecord(rs.getLong(1), rs.getObject(2, UUID.class),
            rs.getString(3), rs.getString(4), rs.getInt(5),
            rs.getObject(6) == null ? null : rs.getInt(6),
            toInstant(rs.getObject(7, OffsetDateTime.class))))
        .list();
  }

  private static WebhookEndpoint mapEndpoint(ResultSet rs) throws SQLException {
    return new WebhookEndpoint(rs.getObject("public_id", UUID.class), URI.create(rs.getString("url")),
        rs.getString("secret"), typesFromJson(rs.getString("event_types")),
        EndpointStatus.valueOf(rs.getString("status")),
        toInstant(rs.getObject("created_at", OffsetDateTime.class)));
  }

  private static String typesJson(List<String> types) {
    if (types == null || types.isEmpty()) {
      return "[]";
    }
    return types.stream().map(t -> "\"" + t + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static List<String> typesFromJson(String json) {
    if (json == null) {
      return List.of();
    }
    String trimmed = json.trim();
    if (trimmed.length() <= 2) {
      return List.of();
    }
    return java.util.Arrays.stream(trimmed.substring(1, trimmed.length() - 1).split(","))
        .map(s -> s.trim().replace("\"", ""))
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime timestamp) {
    return timestamp.toInstant();
  }
}
```

Note: if JdbcClient rejects the `:eventTypes::jsonb` cast syntax, switch to `cast(:eventTypes as jsonb)` — same behavior; do not silently change the fan-out SQL.

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookStoreTest`
Expected: PASS (5 tests). If `:status is null` binding misbehaves with JdbcClient typed nulls, use `(@status::text is null or d.status = @status::text)`; report any deviation.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/webhooks/ \
  src/test/java/com/leandrossb/nummus/webhooks/WebhookStoreTest.java
git commit -m "feat: add the webhook store with write-time fan-out

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: `SignatureHeaders` (pure, TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/SignatureHeaders.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/SignatureHeadersTest.java`

**Interfaces:**
- Produces: `SignatureHeaders.sign(String secret, String payload, Instant now) → "t=<epochSecond>,v1=<hex hmac-sha256(secret, t + "." + payload)>"` — used by Task 6's client; the MAC input is the UTF-8 of `t + "." + payload`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.webhooks.application.SignatureHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureHeadersTest {

  private static final Instant NOW = Instant.ofEpochSecond(1731571200);

  @Test
  void signProducesTimestampedHmacOverDotJoinedInput() throws Exception {
    String header = SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW);

    assertTrue(header.matches("t=1731571200,v1=[0-9a-f]{64}"), "format: " + header);

    // Independent HMAC computation — the receiver-side algorithm, mirrored.
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] expected = mac.doFinal("1731571200.{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    String expectedHex = java.util.HexFormat.of().formatHex(expected);
    assertEquals("t=1731571200,v1=" + expectedHex, header);
  }

  @Test
  void signatureIsSensitiveToTimestampPayloadAndSecret() {
    String base = SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW);
    assertNotEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW.plusSeconds(1)));
    assertNotEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":2}", NOW));
    assertNotEquals(base, SignatureHeaders.sign("whsec_other", "{\"a\":1}", NOW));
    assertEquals(base, SignatureHeaders.sign("whsec_test", "{\"a\":1}", NOW));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=SignatureHeadersTest`
Expected: compilation FAIL — `SignatureHeaders` does not exist.

- [ ] **Step 3: Implement**

```java
package com.leandrossb.nummus.webhooks.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe-style delivery signature: {@code t=<epochSecond>,v1=<hex hmac-sha256(secret,
 * t + "." + payload)>}. Receivers recompute the MAC over the timestamped payload
 * and enforce a tolerance window on {@code t} to reject replays.
 */
public final class SignatureHeaders {

  private SignatureHeaders() {
  }

  public static String sign(String secret, String payload, Instant now) {
    String timestamp = String.valueOf(now.getEpochSecond());
    String mac = hmac(secret, timestamp + "." + payload);
    return "t=" + timestamp + ",v1=" + mac;
  }

  private static String hmac(String secret, String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=SignatureHeadersTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/webhooks/application/SignatureHeaders.java \
  src/test/java/com/leandrossb/nummus/webhooks/SignatureHeadersTest.java
git commit -m "feat: sign webhook deliveries with timestamped HMAC

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Payments event port + outbox adapter + publish calls (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/application/IntentEventTypes.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/IntentLifecycleEvent.java`
- Create: `src/main/java/com/leandrossb/nummus/payments/application/IntentLifecycleEvents.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxIntentLifecycleEvents.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookPublishTest.java`

**Interfaces:**
- Consumes: `WebhookStore.insertEvent` (Task 2), Jackson 3 `ObjectMapper` (M4 lesson: `tools.jackson.databind.ObjectMapper`, unchecked exceptions).
- Produces: `IntentEventTypes.SETTLED/FAILED/EXPIRED` + `ALL` (single source for type strings — Task 7's REST validation reads it); `record IntentLifecycleEvent(String type, UUID publicId, UUID accountPublicId, Money amount, String status, UUID chargePublicId, Instant settledAt, UUID journalTransactionPublicId)`; port `IntentLifecycleEvents { void publish(IntentLifecycleEvent event); }` with exactly one bean, `OutboxIntentLifecycleEvents` (envelope: `{id, type, occurredAt, data:{publicId, accountId, amount (string!), currency, status, chargeId, settledAt?, journalTransactionId?}}`, `amount = toPlainString()`, null fields omitted). `PaymentsServiceImpl` publishes after each successful guarded transition: lazy-expire → `EXPIRED` (re-read intent), FAILED branch → `FAILED` (re-read intent), `settle()` after `markSettled` → `SETTLED` (re-read intent). All in the caller's transaction.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookPublishTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private WebhookStore store;

  private UUID registerEndpoint() {
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(),
        URI.create("https://merchant.example/publish-" + UUID.randomUUID()),
        "whsec_publish", List.of(), EndpointStatus.ACTIVE, Instant.now())).publicId();
  }

  @Test
  void settledIntentPublishesOneEventAndOneDeliveryPerSubscriber() throws Exception {
    var endpointId = registerEndpoint();
    var account = accountsService.open(new OpenAccountCommand("Publish Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("9.0000"), null));
    simulator.pay(intent.chargePublicId());

    payments.get(intent.publicId()); // settles and publishes

    var deliveries = store.listDeliveries(endpointId, null, 50);
    assertEquals(1, deliveries.size());
    assertEquals("payment_intent.settled", deliveries.get(0).eventType());
    assertEquals("PENDING", deliveries.get(0).status());

    // The stored payload is the envelope: string amount, settled state, journal link.
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery("SELECT payload FROM webhooks.webhook_event ORDER BY id DESC LIMIT 1")) {
      rs.next();
      String payload = rs.getString(1);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"type\":\"payment_intent.settled\""), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"amount\":\"9.0000\""), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains(intent.publicId().toString()), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("journalTransactionId"), payload);
    }
  }

  @Test
  void rolledBackSettlementPublishesNothingForThatIntent() throws Exception {
    registerEndpoint();
    var account = accountsService.open(new OpenAccountCommand("Rollback Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("4.0000"), null));
    simulator.pay(intent.chargePublicId());
    accountsService.freeze(account.publicId()); // settle refuses, transaction rolls back

    assertThrows(RuntimeException.class, () -> payments.get(intent.publicId()));

    // Scoped: no event may reference this intent (the shared container legitimately
    // holds other classes' events — never assert global counts).
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery(
            "SELECT count(*) FROM webhooks.webhook_event WHERE payload LIKE '%" + intent.publicId() + "%'")) {
      rs.next();
      assertEquals(0, rs.getInt(1));
    }
  }

  @Test
  void failedAndExpiredIntentsPublishTheirTypes() throws Exception {
    var endpointId = registerEndpoint();
    var failedAccount = accountsService.open(new OpenAccountCommand("Failed Merchant"));
    var failed = payments.create(new CreateIntentCommand(failedAccount.publicId(), Money.ofBrl("3.0000"), null));
    simulator.fail(failed.chargePublicId());
    payments.get(failed.publicId());

    var expiredAccount = accountsService.open(new OpenAccountCommand("Expired Merchant"));
    var expired = payments.create(new CreateIntentCommand(expiredAccount.publicId(), Money.ofBrl("2.0000"), null));
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET expires_at = now() - interval '1 second' "
          + "WHERE public_id = '" + expired.publicId() + "'");
    }
    payments.get(expired.publicId());

    var deliveries = store.listDeliveries(endpointId, null, 50);
    assertEquals(2, deliveries.size());
    // listDeliveries orders by delivery id DESC: the expired event (published
    // second) comes first.
    assertEquals("payment_intent.expired", deliveries.get(0).eventType());
    assertEquals("payment_intent.failed", deliveries.get(1).eventType());
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookPublishTest`
Expected: FAIL — nothing publishes (`listDeliveries` empty / count 0 where events expected).

- [ ] **Step 3: Write the port, adapter, and publish calls**

`payments/application/IntentEventTypes.java`:
```java
package com.leandrossb.nummus.payments.application;

import java.util.Set;

/** Single source of the intent lifecycle event type strings (webhook catalog mirrors this). */
public final class IntentEventTypes {

  public static final String SETTLED = "payment_intent.settled";
  public static final String FAILED = "payment_intent.failed";
  public static final String EXPIRED = "payment_intent.expired";
  public static final Set<String> ALL = Set.of(SETTLED, FAILED, EXPIRED);

  private IntentEventTypes() {
  }
}
```

`payments/application/IntentLifecycleEvent.java`:
```java
package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A completed intent transition, handed to the outbox in the same transaction. */
public record IntentLifecycleEvent(
    String type, UUID publicId, UUID accountPublicId, Money amount, String status,
    UUID chargePublicId, Instant settledAt, UUID journalTransactionPublicId) {
}
```

`payments/application/IntentLifecycleEvents.java`:
```java
package com.leandrossb.nummus.payments.application;

/**
 * Producer-owned port implemented by the webhooks module (the M3 inversion:
 * the producing module defines the port, the consuming module adapts to it).
 * Implementations join the caller's transaction — publish after the guarded
 * transition succeeds, never before.
 */
public interface IntentLifecycleEvents {

  void publish(IntentLifecycleEvent event);
}
```

`webhooks/infrastructure/OutboxIntentLifecycleEvents.java`:
```java
package com.leandrossb.nummus.webhooks.infrastructure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvent;
import com.leandrossb.nummus.payments.application.IntentLifecycleEvents;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the serialized envelope and its fan-out delivery rows inside the
 * caller's transaction: the event commits with the state change or not at
 * all. The envelope is serialized exactly once — every delivery sends these
 * stored bytes.
 */
@Component
public class OutboxIntentLifecycleEvents implements IntentLifecycleEvents {

  private final WebhookStore store;
  private final ObjectMapper objectMapper;

  public OutboxIntentLifecycleEvents(WebhookStore store, ObjectMapper objectMapper) {
    this.store = store;
    this.objectMapper = objectMapper;
  }

  @Override
  public void publish(IntentLifecycleEvent event) {
    UUID eventId = UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(new Envelope(
        eventId, event.type(), Instant.now(), new Data(
            event.publicId(), event.accountPublicId(),
            event.amount().amount().toPlainString(),
            event.amount().currency().getCurrencyCode(),
            event.status(), event.chargePublicId(),
            event.settledAt(), event.journalTransactionPublicId())));
    store.insertEvent(eventId, event.type(), payload, Instant.now());
  }

  record Envelope(UUID id, String type, Instant occurredAt, Data data) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Data(UUID publicId, UUID accountId, String amount, String currency,
      String status, UUID chargeId, Instant settledAt, UUID journalTransactionId) {
  }
}
```

`PaymentsServiceImpl` changes — add the dependency and three publish calls:
1. Field + constructor parameter `private final IntentLifecycleEvents intentEvents;` (import `com.leandrossb.nummus.payments.application.IntentLifecycleEvents` is same package — no import needed; add `import` for `IntentEventTypes` likewise unnecessary, same package).
2. In `get()`, lazy-expire branch:
```java
    if (Instant.now().isAfter(intent.expiresAt())) {
      repository.transitionToExpired(publicId);
      var expired = repository.findByPublicId(publicId).orElseThrow();
      intentEvents.publish(toEvent(IntentEventTypes.EXPIRED, expired));
      return expired;
    }
```
3. FAILED branch:
```java
      case FAILED -> {
        repository.transitionToFailed(publicId);
        var failed = repository.findByPublicId(publicId).orElseThrow();
        intentEvents.publish(toEvent(IntentEventTypes.FAILED, failed));
        yield failed;
      }
```
4. In `settle()`, after the guarded transition wins:
```java
    if (!repository.markSettled(intent.publicId(), posted.publicId(), Instant.now())) {
      throw new ConcurrentSettlementException(intent.publicId());
    }
    var settled = repository.findByPublicId(intent.publicId()).orElseThrow();
    intentEvents.publish(toEvent(IntentEventTypes.SETTLED, settled));
    return settled;
```
5. Private helper at the bottom of the class:
```java
  private static IntentLifecycleEvent toEvent(String type, PaymentIntent intent) {
    return new IntentLifecycleEvent(type, intent.publicId(), intent.accountPublicId(),
        intent.amount(), intent.status().name(), intent.chargePublicId(),
        intent.settledAt(), intent.journalTransactionPublicId());
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookPublishTest`
Expected: PASS (3 tests). Then full suite: `<env-prefix> ./mvnw verify` → green, 146 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/application/ \
  src/main/java/com/leandrossb/nummus/webhooks/infrastructure/OutboxIntentLifecycleEvents.java \
  src/test/java/com/leandrossb/nummus/webhooks/WebhookPublishTest.java
git commit -m "feat: publish intent lifecycle events to the transactional outbox

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: Delivery worker with bounded backoff (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookProperties.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/EventDeliveryClient.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/DeliveryResult.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookDeliveryWorker.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryWorkerTest.java`

**Interfaces:**
- Consumes: `WebhookStore.claimDueDeliveries/recordDeliverySuccess/recordDeliveryRetry/recordDeliveryFailure` + `DueDelivery` (Task 2).
- Produces: `record WebhookProperties(int maxAttempts, Duration backoffBase, int batchSize)` bound at `nummus.webhooks.*` (`@DefaultValue`s 8 / PT2S / 50); port `EventDeliveryClient { DeliveryResult deliver(URI url, String secret, String eventType, String payload); }`; `record DeliveryResult(boolean delivered, Integer httpStatus)`; `WebhookDeliveryWorker` with `@Scheduled(fixedDelayString = "${nummus.webhooks.poll-delay-ms:1000}", initialDelayString = "${nummus.webhooks.initial-delay-ms:1000}") public void deliverDue()` (tests call `deliverDue()` directly; the test context disables the schedule via properties). Worker policy: endpoint not ACTIVE → `recordDeliveryFailure(id, null)` without calling the client; delivered → `recordDeliverySuccess(id, result.httpStatus())`; `attempts + 1 >= maxAttempts` → `recordDeliveryFailure`; else `recordDeliveryRetry(id, httpStatus, now + backoffBase × 2^(attempts+1))`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.DeliveryResult;
import com.leandrossb.nummus.webhooks.application.EventDeliveryClient;
import com.leandrossb.nummus.webhooks.application.WebhookDeliveryWorker;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Owns a context with the schedule neutralized (poll/initial pushed to 1h). */
@SpringBootTest(properties = {
    "nummus.webhooks.poll-delay-ms=3600000",
    "nummus.webhooks.initial-delay-ms=3600000"})
class WebhookDeliveryWorkerTest extends IntegrationTestBase {

  @Autowired
  private WebhookStore store;

  @Autowired
  private WebhookDeliveryWorker worker;

  @Autowired
  private FakeClient client;

  /**
   * The PostgreSQL container is shared across the whole suite — start clean so
   * cross-class leftovers can never be claimed by these exact-count assertions.
   */
  @org.junit.jupiter.api.BeforeAll
  static void cleanWebhooksTables() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE webhooks.webhook_delivery, webhooks.webhook_event, webhooks.webhook_endpoint");
    }
  }

  @TestConfiguration
  static class FakeClientConfig {
    @Bean
    @Primary
    FakeClient fakeClient() {
      return new FakeClient();
    }
  }

  /** Records per-URL call counts — claim batches may include other tests' rows. */
  static class FakeClient implements EventDeliveryClient {
    final List<URI> urls = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean succeed;
    volatile Integer status = 200;

    long callsFor(String urlSuffix) {
      return urls.stream().filter(u -> u.toString().endsWith(urlSuffix)).count();
    }

    @Override
    public DeliveryResult deliver(URI url, String secret, String eventType, String payload) {
      urls.add(url);
      return new DeliveryResult(succeed, status);
    }
  }

  private UUID endpoint(String path) {
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(),
        URI.create("https://merchant.example/worker-" + path), "whsec_worker",
        List.of(), EndpointStatus.ACTIVE, Instant.now())).publicId();
  }

  private void publish() {
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
  }

  @Test
  void successfulDeliveryIsRecordedOnceAndNotReclaimed() {
    var endpointId = endpoint("success");
    publish();
    client.succeed = true;

    worker.deliverDue();
    worker.deliverDue(); // nothing due anymore for this endpoint

    assertEquals(1, client.callsFor("/worker-success"));
    assertEquals(1, store.listDeliveries(endpointId, "SUCCEEDED", 50).size());
  }

  @Test
  void failedAttemptSchedulesExponentialBackoff() {
    var endpointId = endpoint("backoff");
    publish();
    client.succeed = false;
    client.status = 500;

    worker.deliverDue();
    worker.deliverDue(); // still within the backoff window — no second attempt

    assertEquals(1, client.callsFor("/worker-backoff"));
    var record = store.listDeliveries(endpointId, null, 50).get(0);
    assertEquals("PENDING", record.status());
    assertEquals(1, record.attempts());
    assertEquals(500, record.lastResponseStatus());
  }

  @Test
  void exhaustedBudgetFailsPermanently() throws Exception {
    var endpointId = endpoint("exhaust");
    publish();
    client.succeed = false;
    client.status = 503;

    // Age the row DB-side to make every retry due immediately (skew-safe).
    for (int i = 0; i < 8; i++) {
      try (var c = adminConnection(); var st = c.createStatement()) {
        st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() - interval '1 second' "
            + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/worker-exhaust')");
      }
      worker.deliverDue();
    }

    var record = store.listDeliveries(endpointId, null, 50).get(0);
    assertEquals("FAILED", record.status());
    assertEquals(8, record.attempts());
    assertEquals(8, client.callsFor("/worker-exhaust"));
  }

  @Test
  void deletedEndpointFailsDeliveryWithoutAnHttpRequest() {
    var endpointId = endpoint("deleted");
    publish();
    store.markEndpointDeleted(endpointId);
    client.succeed = true;

    worker.deliverDue();

    assertEquals(0, client.callsFor("/worker-deleted"));
    assertEquals(1, store.listDeliveries(endpointId, "FAILED", 50).size());
  }
}
```

Note: the `@TestConfiguration` `FakeClient` is the `@Primary` `EventDeliveryClient` — the real `RestClientEventDeliveryClient` does not exist yet (Task 6); the worker bean injects the fake. `deliverDue()` is the public `@Scheduled` method; tests call it directly (the context's schedule is pushed to 1h by the properties above).

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookDeliveryWorkerTest`
Expected: compilation FAIL — the worker/properties/port types do not exist.

- [ ] **Step 3: Implement**

`application/WebhookProperties.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Delivery retry policy: 8 attempts, delays backoffBase x 2^attempt (4s, 8s, ... 256s at PT2S). */
@ConfigurationProperties(prefix = "nummus.webhooks")
public record WebhookProperties(
    @DefaultValue("8") int maxAttempts,
    @DefaultValue("PT2S") Duration backoffBase,
    @DefaultValue("50") int batchSize) {
}
```

`application/EventDeliveryClient.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import java.net.URI;

/** The outbound HTTP boundary, isolated as a port so the retry policy is testable without a server. */
public interface EventDeliveryClient {

  DeliveryResult deliver(URI url, String secret, String eventType, String payload);
}
```

`application/DeliveryResult.java`:
```java
package com.leandrossb.nummus.webhooks.application;

/** Outcome of one delivery attempt; httpStatus is null when no HTTP response was received. */
public record DeliveryResult(boolean delivered, Integer httpStatus) {
}
```

`application/WebhookDeliveryWorker.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At-least-once delivery: claims due PENDING rows, POSTs the exact stored
 * payload bytes, and records success / bounded exponential backoff / permanent
 * failure. Single-process by design (fixedDelay never overlaps itself);
 * scale-out needs SKIP LOCKED claiming — see the backlog.
 */
@Component
public class WebhookDeliveryWorker {

  private final WebhookStore store;
  private final EventDeliveryClient client;
  private final WebhookProperties properties;

  public WebhookDeliveryWorker(WebhookStore store, EventDeliveryClient client,
      WebhookProperties properties) {
    this.store = store;
    this.client = client;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.webhooks.poll-delay-ms:1000}",
      initialDelayString = "${nummus.webhooks.initial-delay-ms:1000}")
  public void deliverDue() {
    for (var due : store.claimDueDeliveries(Instant.now(), properties.batchSize())) {
      if (due.endpointStatus() != EndpointStatus.ACTIVE) {
        // Unsubscribed mid-flight: stop trying, keep the history.
        store.recordDeliveryFailure(due.id(), null);
        continue;
      }
      var result = client.deliver(due.url(), due.secret(), due.eventType(), due.payload());
      if (result.delivered()) {
        store.recordDeliverySuccess(due.id(), result.httpStatus());
      } else if (due.attempts() + 1 >= properties.maxAttempts()) {
        store.recordDeliveryFailure(due.id(), result.httpStatus());
      } else {
        store.recordDeliveryRetry(due.id(), result.httpStatus(),
            Instant.now().plus(backoffAfter(due.attempts() + 1)));
      }
    }
  }

  private Duration backoffAfter(int attempt) {
    return properties.backoffBase().multipliedBy((long) Math.pow(2, attempt));
  }
}
```

Also register `WebhookProperties` for binding: it carries `@ConfigurationProperties`, and `Application` already has `@ConfigurationPropertiesScan` (M4) — no further wiring.

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookDeliveryWorkerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/webhooks/application/ \
  src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryWorkerTest.java
git commit -m "feat: deliver webhook events with bounded exponential backoff

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: `RestClientEventDeliveryClient` + end-to-end over real HTTP (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/RestClientEventDeliveryClient.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/ReceiverServer.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryClientTest.java`

**Interfaces:**
- Consumes: `EventDeliveryClient` port (Task 5), `SignatureHeaders.sign` (Task 3), Spring `RestClient.Builder` (auto-configured), `WebhookStore`/worker (Tasks 2/5).
- Produces: the production HTTP adapter — POST `payload` with `Content-Type: application/json`, `Nummus-Signature`, `Nummus-Event: <type>`; connect timeout 2s, read timeout 5s; 2xx → `DeliveryResult(true, status)`; non-2xx HTTP → `(false, status)`; IO/timeout → `(false, null)`. `ReceiverServer` — test-only JDK `HttpServer` on an ephemeral port capturing method/headers/body and returning a configurable status.

- [ ] **Step 1: Write the failing test** (server helper first)

`ReceiverServer.java`:
```java
package com.leandrossb.nummus.webhooks;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test receiver: a JDK HttpServer on an ephemeral port capturing every request. */
final class ReceiverServer implements AutoCloseable {

  record Received(String method, String path, Map<String, String> headers, String body) {
  }

  final List<Received> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
  private final HttpServer server;

  ReceiverServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (InputStream body = exchange.getRequestBody()) {
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
        requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            headers, new String(body.readAllBytes(), StandardCharsets.UTF_8)));
      }
      Integer status = statusByPath.getOrDefault(exchange.getRequestURI().getPath(), 200);
      byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      try (var out = exchange.getResponseBody()) {
        out.write(response);
      }
    });
    server.start();
  }

  void respondWith(String path, int status) {
    statusByPath.put(path, status);
  }

  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
```

`WebhookDeliveryClientTest.java`:
```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.EventDeliveryClient;
import com.leandrossb.nummus.webhooks.application.WebhookDeliveryWorker;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Schedule neutralized (same properties keys as the worker test; the nested
 *  @TestConfiguration there splits the contexts). */
@SpringBootTest(properties = {
    "nummus.webhooks.poll-delay-ms=3600000",
    "nummus.webhooks.initial-delay-ms=3600000"})
class WebhookDeliveryClientTest extends IntegrationTestBase {

  @Autowired
  private EventDeliveryClient client;

  @Autowired
  private WebhookStore store;

  @Autowired
  private WebhookDeliveryWorker worker;

  @org.junit.jupiter.api.BeforeAll
  static void cleanWebhooksTables() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE webhooks.webhook_delivery, webhooks.webhook_event, webhooks.webhook_endpoint");
    }
  }

  @Test
  void deliversSignedByteExactRequestOverHttp() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      String payload = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"payment_intent.settled\"}";
      var result = client.deliver(URI.create(receiver.url("/hook")), "whsec_e2e",
          "payment_intent.settled", payload);

      assertTrue(result.delivered());
      assertEquals(200, result.httpStatus());
      assertEquals(1, receiver.requests.size());
      var received = receiver.requests.get(0);
      assertEquals("POST", received.method());
      assertEquals(payload, received.body()); // byte-exact stored payload
      assertEquals("payment_intent.settled", received.headers().get("Nummus-event"));
      assertTrue(received.headers().get("Nummus-signature").matches("t=\\d+,v1=[0-9a-f]{64}"),
          received.headers().get("Nummus-signature"));

      // Receiver-side verification: recompute the MAC exactly as a merchant would.
      String header = received.headers().get("Nummus-signature");
      String timestamp = header.substring(2, header.indexOf(','));
      String expected = com.leandrossb.nummus.webhooks.application.SignatureHeaders.sign(
          "whsec_e2e", payload, Instant.ofEpochSecond(Long.parseLong(timestamp)));
      assertEquals(expected, header);
    }
  }

  @Test
  void nonSuccessResponsesAreReportedNotThrown() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      receiver.respondWith("/flaky", 500);
      var result = client.deliver(URI.create(receiver.url("/flaky")), "whsec_e2e",
          "payment_intent.settled", "{}");
      assertFalse(result.delivered());
      assertEquals(500, result.httpStatus());
    }
  }

  @Test
  void endToEndSettledIntentIsDeliveredWithAValidSignature() throws Exception {
    try (ReceiverServer receiver = new ReceiverServer()) {
      var endpoint = store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(),
          URI.create(receiver.url("/merchant")), "whsec_flow", List.of(),
          EndpointStatus.ACTIVE, Instant.now()));
      store.insertEvent(UUID.randomUUID(), "payment_intent.settled",
          "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"payment_intent.settled\",\"data\":{\"amount\":\"7.0000\"}}",
          Instant.now());

      worker.deliverDue();

      assertEquals(1, receiver.requests.size());
      var received = receiver.requests.get(0);
      assertTrue(received.body().contains("\"amount\":\"7.0000\""));
      assertEquals("SUCCEEDED",
          store.listDeliveries(endpoint.publicId(), null, 50).get(0).status());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookDeliveryClientTest`
Expected: FAIL — no `EventDeliveryClient` bean can satisfy the context (the port from Task 5 has no production implementation; if the context fails to start, that is this task's RED).

- [ ] **Step 3: Implement the adapter**

```java
package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.webhooks.application.DeliveryResult;
import com.leandrossb.nummus.webhooks.application.EventDeliveryClient;
import com.leandrossb.nummus.webhooks.application.SignatureHeaders;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Production HTTP delivery: the exact stored payload bytes, signed, with tight timeouts. */
@Component
public class RestClientEventDeliveryClient implements EventDeliveryClient {

  private final RestClient restClient;

  public RestClientEventDeliveryClient(RestClient.Builder builder) {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
    this.restClient = builder.requestFactory(factory).build();
  }

  @Override
  public DeliveryResult deliver(URI url, String secret, String eventType, String payload) {
    try {
      var response = restClient.post().uri(url)
          .contentType(MediaType.APPLICATION_JSON)
          .header("Nummus-Signature", SignatureHeaders.sign(secret, payload, Instant.now()))
          .header("Nummus-Event", eventType)
          .body(payload)
          .retrieve()
          .toBodilessEntity();
      return new DeliveryResult(response.getStatusCode().is2xxAligned(), response.getStatusCode().value());
    } catch (RestClientResponseException e) {
      return new DeliveryResult(false, e.getStatusCode().value());
    } catch (ResourceAccessException e) {
      return new DeliveryResult(false, null); // connect/read failure or timeout
    }
  }
}
```

Boot 4 API notes: `is2xxAligned()` is the 6.x+ successor of `is2xxSuccessful()`. If the worker test's `@Primary` fake now conflicts (it doesn't — different context configuration), do not weaken either test; report.

- [ ] **Step 4: Run tests to verify they pass**

Run: `<env-prefix> ./mvnw test -Dtest='WebhookDeliveryClientTest,WebhookDeliveryWorkerTest'`
Expected: PASS (3 + 4 tests; separate contexts because the worker class adds a `@TestConfiguration`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/webhooks/infrastructure/RestClientEventDeliveryClient.java \
  src/test/java/com/leandrossb/nummus/webhooks/ReceiverServer.java \
  src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryClientTest.java
git commit -m "feat: deliver webhooks over HTTP with signed exact payloads

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Webhook endpoint REST API (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/WebhookEndpointsController.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/WebhookDeliveriesController.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/CreateEndpointRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/CreateEndpointResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/EndpointResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/DeliveryResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java` (add `UnknownWebhookEndpointException` to the existing 404 group)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookEndpointsRestApiTest.java`

**Interfaces:**
- Consumes: `WebhookStore` (Task 2), `IntentEventTypes.ALL` (Task 4, payments.application — allowed dependency), `@Idempotent` + M4 filter (POST `/v1/**` demands `Idempotency-Key`), `IllegalArgumentException → 400` existing handler.
- Produces: `WebhookEndpointsService { WebhookEndpoint register(URI url, List<String> eventTypes); List<WebhookEndpoint> list(); WebhookEndpoint get(UUID); void delete(UUID); }` — register validates `eventTypes ⊆ IntentEventTypes.ALL` (else `IllegalArgumentException`), generates the secret (`SecureRandom` 32 bytes, base64url, no padding). REST: `POST /v1/webhook-endpoints` (`@Idempotent`, 201 + `Location`, body includes `secret` exactly once), `GET /v1/webhook-endpoints` (active only, no secret), `GET /{id}` (404 unknown/deleted via `UnknownWebhookEndpointException`), `DELETE /{id}` (204, soft), `GET /{id}/deliveries?status=` (404 for unknown endpoint; `DeliveryResponse` list).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class WebhookEndpointsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  private String createEndpoint(String body) throws Exception {
    MvcResult result = mockMvc.perform(post("/v1/webhook-endpoints")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
  }

  @Test
  void createReturnsTheSecretExactlyOnceAndReplaysIdentically() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"url\":\"https://merchant.example/hook\",\"eventTypes\":[\"payment_intent.settled\"]}";
    var first = mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").exists())
        .andExpect(jsonPath("$.url").value("https://merchant.example/hook"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn();
    var replay = mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andReturn();
    org.junit.jupiter.api.Assertions.assertEquals(
        first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

    // The secret never appears again: get and list omit it.
    String location = first.getResponse().getHeader("Location");
    mockMvc.perform(get(location)).andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").doesNotExist());
    mockMvc.perform(get("/v1/webhook-endpoints")).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].secret").doesNotExist());
  }

  @Test
  void createValidatesUrlSchemeAndEventTypeCatalog() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"ftp://merchant.example/hook\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/hook\",\"eventTypes\":[\"nope.event\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRequiresAnIdempotencyKey() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/hook\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAndDeletedEndpointsAre404() throws Exception {
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
    String location = createEndpoint("{\"url\":\"https://merchant.example/temp\"}");
    mockMvc.perform(delete(location)).andExpect(status().isNoContent());
    mockMvc.perform(get(location)).andExpect(status().isNotFound());
    mockMvc.perform(delete(location)).andExpect(status().isNotFound());
  }

  @Test
  void deliveriesAreListedPerEndpoint() throws Exception {
    String location = createEndpoint("{\"url\":\"https://merchant.example/dl\"}");
    String endpointId = location.substring(location.lastIndexOf('/') + 1);
    mockMvc.perform(get(location + "/deliveries")).andExpect(status().isOk());
    mockMvc.perform(get(location + "/deliveries?status=SUCCEEDED")).andExpect(status().isOk());
    mockMvc.perform(get("/v1/webhook-endpoints/" + UUID.randomUUID() + "/deliveries"))
        .andExpect(status().isNotFound());
    org.junit.jupiter.api.Assertions.assertFalse(endpointId.isEmpty());
  }

  @Test
  void eventTypesDefaultToAllWhenOmitted() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://merchant.example/all\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.eventTypes").isArray());
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookEndpointsRestApiTest`
Expected: FAIL — 404s/405s (no controllers).

- [ ] **Step 3: Implement service, DTOs, controllers, handler entry**

`application/WebhookEndpointsService.java`:
```java
package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.payments.application.IntentEventTypes;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Subscription lifecycle. The signing secret is generated here and shown to callers exactly once. */
@Service
public class WebhookEndpointsService {

  private final WebhookStore store;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointsService(WebhookStore store) {
    this.store = store;
  }

  public WebhookEndpoint register(URI url, List<String> eventTypes) {
    List<String> types = eventTypes == null ? List.of() : eventTypes;
    List<String> unknown = types.stream().filter(t -> !IntentEventTypes.ALL.contains(t)).toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown event types: " + unknown);
    }
    byte[] secretBytes = new byte[32];
    random.nextBytes(secretBytes);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(), url, secret,
        types, com.leandrossb.nummus.webhooks.domain.EndpointStatus.ACTIVE, Instant.now()));
  }

  public List<WebhookEndpoint> list() {
    return store.listActiveEndpoints();
  }

  public WebhookEndpoint get(UUID publicId) {
    return store.findActiveEndpoint(publicId).orElseThrow(() -> new UnknownWebhookEndpointException(publicId));
  }

  public void delete(UUID publicId) {
    if (!store.markEndpointDeleted(publicId)) {
      throw new UnknownWebhookEndpointException(publicId);
    }
  }
}
```

`interfaces/dto/CreateEndpointRequest.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;

/** Request body for registering a webhook endpoint. Event types validate against the payments catalog. */
public record CreateEndpointRequest(
    @NotBlank(message = "url must not be blank")
    @Pattern(regexp = "^https?://.+", message = "url must be an http(s) URL") URI url,
    List<String> eventTypes) {
}
```

`interfaces/dto/CreateEndpointResponse.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Create response — the ONLY surface that ever carries the signing secret. */
public record CreateEndpointResponse(
    UUID publicId, String url, List<String> eventTypes, String status,
    Instant createdAt, String secret) {

  public static CreateEndpointResponse from(WebhookEndpoint endpoint) {
    return new CreateEndpointResponse(endpoint.publicId(), endpoint.url().toString(),
        endpoint.eventTypes(), endpoint.status().name(), endpoint.createdAt(), endpoint.secret());
  }
}
```

`interfaces/dto/EndpointResponse.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Endpoint view without the secret. */
public record EndpointResponse(
    UUID publicId, String url, List<String> eventTypes, String status, Instant createdAt) {

  public static EndpointResponse from(WebhookEndpoint endpoint) {
    return new EndpointResponse(endpoint.publicId(), endpoint.url().toString(),
        endpoint.eventTypes(), endpoint.status().name(), endpoint.createdAt());
  }
}
```

`interfaces/dto/DeliveryResponse.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces.dto;

import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
    UUID eventId, String eventType, String status, int attempts,
    Integer lastResponseStatus, Instant nextAttemptAt) {

  public static DeliveryResponse from(DeliveryRecord record) {
    return new DeliveryResponse(record.eventPublicId(), record.eventType(), record.status(),
        record.attempts(), record.lastResponseStatus(), record.nextAttemptAt());
  }
}
```

`interfaces/WebhookEndpointsController.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces;

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

@RestController
@RequestMapping("/v1/webhook-endpoints")
class WebhookEndpointsController {

  private final WebhookEndpointsService endpoints;

  WebhookEndpointsController(WebhookEndpointsService endpoints) {
    this.endpoints = endpoints;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateEndpointResponse> create(@Valid @RequestBody CreateEndpointRequest request) {
    var endpoint = endpoints.register(request.url(), request.eventTypes());
    return ResponseEntity
        .created(URI.create("/v1/webhook-endpoints/" + endpoint.publicId()))
        .body(CreateEndpointResponse.from(endpoint));
  }

  @GetMapping
  java.util.List<EndpointResponse> list() {
    return endpoints.list().stream().map(EndpointResponse::from).toList();
  }

  @GetMapping("/{id}")
  EndpointResponse get(@PathVariable UUID id) {
    return EndpointResponse.from(endpoints.get(id));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id) {
    endpoints.delete(id);
    return ResponseEntity.noContent().build();
  }
}
```

`interfaces/WebhookDeliveriesController.java`:
```java
package com.leandrossb.nummus.webhooks.interfaces;

import com.leandrossb.nummus.webhooks.application.WebhookEndpointsService;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.interfaces.dto.DeliveryResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WebhookDeliveriesController {

  private final WebhookEndpointsService endpoints;
  private final WebhookStore store;

  WebhookDeliveriesController(WebhookEndpointsService endpoints, WebhookStore store) {
    this.endpoints = endpoints;
    this.store = store;
  }

  @GetMapping("/v1/webhook-endpoints/{id}/deliveries")
  java.util.List<DeliveryResponse> deliveries(@PathVariable UUID id,
      @RequestParam(required = false) String status) {
    endpoints.get(id); // 404 for unknown or deleted endpoints
    return store.listDeliveries(id, status, 50).stream().map(DeliveryResponse::from).toList();
  }
}
```

`GlobalExceptionHandler` — add `UnknownWebhookEndpointException.class` to the existing `notFound` group (import `com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException`), no new method.

- [ ] **Step 4: Run tests to verify they pass**

Run: `<env-prefix> ./mvnw test -Dtest=WebhookEndpointsRestApiTest`
Expected: PASS (6 tests). Then full suite: `<env-prefix> ./mvnw verify` → green, 162 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ \
  src/test/java/com/leandrossb/nummus/webhooks/WebhookEndpointsRestApiTest.java
git commit -m "feat: add webhook endpoint subscription REST API

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: ArchUnit rule for the webhooks module boundary

**Files:**
- Modify: `src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java`

**Interfaces:**
- Consumes: ArchUnit idioms from the existing rules.
- Produces: rule `webhooksTouchesOnlyThePaymentsPort` — `webhooks` may depend on `payments.application` (the `IntentLifecycleEvents` port + `IntentEventTypes` + `IntentLifecycleEvent`) and on the shared kernel (`ledger.domain.Money` via the event record), but never on `payments.domain`, `payments.infrastructure`, `payments.interfaces`, or anything under `accounts`. Mirrors `simulatorTouchesOnlyTheNetworkPort`.

- [ ] **Step 1: Add the rule** (append inside `ModuleBoundaryTest`)

```java
  @ArchTest
  static final ArchRule webhooksTouchesOnlyThePaymentsPort =
      noClasses().that().resideInAPackage("..webhooks..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..payments.domain..", "..payments.infrastructure..",
              "..payments.interfaces..", "..accounts..");
```

- [ ] **Step 2: Run the class**

Run: `<env-prefix> ./mvnw test -Dtest=ModuleBoundaryTest`
Expected: PASS (9 tests — the 8 existing rules + this one). If the new rule fails, the violating dependency is a real finding — do not weaken the rule; report.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java
git commit -m "test: ban webhooks from reaching past the payments port

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md`
- Modify: `docs/m2-backlog.md`

- [ ] **Step 1: Full verify**

Run: `<env-prefix> ./mvnw verify`
Expected: `BUILD SUCCESS`, `Tests run: 163, Failures: 0, Errors: 0, Skipped: 0`.

- [ ] **Step 2: Update `README.md`**

```markdown
- [x] M5 — Webhooks via transactional outbox
```

- [ ] **Step 3: Append to `docs/m2-backlog.md`** (new section)

```markdown
## From the M5 review

M5 delivered the transactional outbox with write-time fan-out, signed
at-least-once delivery, and bounded exponential backoff. Known bounds,
deliberate:

- **Single-process worker.** `fixedDelay` self-exclusivity is the only guard;
  a second instance would double-deliver (still at-least-once-correct, but
  wasteful). Scale-out needs `FOR UPDATE SKIP LOCKED` claiming.
- **No delivery retention/pruning.** `webhook_delivery` rows accumulate;
  add a retention job (and a `delete` grant) when volume demands it.
- **No manual redrive.** FAILED deliveries stay failed; a retry API is a
  natural follow-up.
- **`GET /deliveries` is unpaginated** (fixed limit 50) and unauthenticated,
  like every other endpoint until merchant auth lands.
- **Receiver-side replay tolerance is documented, not enforced** — the
  signature carries `t=`, but tolerance windows are the receiver's choice.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/m2-backlog.md
git commit -m "docs: mark M5 webhooks outbox complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 5: Report**

Report the final `./mvnw verify` summary line and test count verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V8 schema + grants (+ roles exercised) | 1 |
| Store: endpoints, event insert with write-time fan-out, claim, outcomes, listing | 2 |
| Signature `t=`/`v1=` HMAC | 3, 6 |
| Producer-owned port + same-transaction publish on settle/fail/expire | 4 |
| Worker: backoff policy, exhaustion, deleted-endpoint handling, single-process schedule | 5 |
| Production HTTP client (timeouts, signed byte-exact POST, `Nummus-Event`) | 6 |
| REST subscriptions (secret once, validation, soft delete, deliveries) + 404 vocabulary | 7 |
| ArchUnit boundary for webhooks | 8 |
| Success criteria, README, backlog | 9 |
```

