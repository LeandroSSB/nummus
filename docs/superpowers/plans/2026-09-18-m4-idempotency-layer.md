# M4 Idempotency Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merchant-facing POSTs require an `Idempotency-Key`; the first execution's response is stored in the same transaction as the business write, and retries replay it verbatim — exactly-once execution per key.

**Architecture:** A filter validates the header (fail-closed on POST `/v1/**`) and caches the request body; an `@Around` aspect on `@Idempotent` handler methods opens the transaction, reserves the key, runs the handler, and attaches the serialized response — all atomically. Business services (`@Transactional REQUIRED`) join the aspect's transaction. Domain exceptions roll everything back and re-execute deterministically on retry.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (web, jdbc, validation, flyway, **aop — added in Task 4**), PostgreSQL via Testcontainers, JUnit 5 + MockMvc, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-18-m4-idempotency-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`, `test:`).
- **Every `./mvnw` invocation needs the Docker-tunnel env prefix** (Testcontainers runs against the megalan server's Docker daemon; local Docker is broken):
  ```
  JAVA_HOME=~/.jdks/jdk-25.0.4.1+1 DOCKER_HOST=unix:///tmp/megalan-docker.sock \
  TESTCONTAINERS_HOST_OVERRIDE=192.168.0.210 TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ```
  Verify the tunnel first: `curl -s --max-time 3 --unix-socket /tmp/megalan-docker.sock http://localhost/_ping` must print `OK`. If dead, start it: `ssh -nNT -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L /tmp/megalan-docker.sock:/var/run/docker.sock megalan` (background). Running without the prefix fails every Testcontainers test with `Could not find a valid Docker environment` — this looks like a code failure but is not.
- **Test classes end in `Test`.** Success criteria: `./mvnw verify` green at every commit; final count **133 tests** (118 today + 15 new: 1 schema, 4 store, 2 filter, 4 replay, 2 expiry/purge, 1 concurrency, 1 amount-bound).
- **Every task leaves `main` green**: the filter (Task 3) changes the REST contract, so the same task updates every existing REST test to send the header.
- **ArchUnit rule `persistenceTypesOnlyInInfrastructure`** bans `org.springframework.jdbc..`/`java.sql..` outside `..infrastructure..` packages — the JDBC adapter MUST live in `com.leandrossb.nummus.interfaces.idempotency.infrastructure`.
- **Stop and ask** if MockMvc does not execute the filter (Task 3's test is the canary) or if `RequestContextHolder` is unpopulated during handler execution (Task 4's test is the canary).
- Money stays `BigDecimal`/`Money`; this milestone touches no money code.

## File Map (final state after all tasks)

```
pom.xml                                                              (Task 4: starter-aop)
src/main/resources/db/migration/V7__idempotency_schema.sql           (Task 1)
src/main/java/com/leandrossb/nummus/interfaces/idempotency/
  IdempotencyStore.java, StoredResponse.java, StoredRow.java         (Task 2)
  infrastructure/JdbcClientIdempotencyStore.java                     (Task 2)
  IdempotencyWebFilter.java, CachedBodyRequest.java,
  RequestFingerprinter.java                                          (Task 3)
  Idempotent.java, IdempotencyKeyReuseException.java,
  IdempotencyProperties.java, IdempotencyAspect.java                 (Task 4)
  IdempotencyPurgeJob.java                                           (Task 5)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (Task 4: 422 entry)
src/main/java/com/leandrossb/nummus/Application.java                 (Tasks 4+5: scan + scheduling)
src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java (Task 4: @Idempotent x4)
src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java (Task 4: @Idempotent)
src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreateIntentRequest.java (Task 7: @Digits)
src/test/java/com/leandrossb/nummus/idempotency/
  IdempotencySchemaTest.java                                         (Task 1)
  IdempotencyStoreTest.java                                          (Task 2)
  IdempotencyRestApiTest.java                                        (Tasks 3, 4, 7)
  IdempotencyExpiryTest.java                                         (Task 5)
  IdempotencyPurgeTest.java                                          (Task 5)
  IdempotencyConcurrencyTest.java                                    (Task 6)
src/test/java/com/leandrossb/nummus/payments/PaymentsRestApiTest.java (Task 3: header on 6 posts)
src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java (Task 3: header on 8 posts)
README.md, docs/m2-backlog.md                                        (Task 8)
```

---

### Task 1: `V7__idempotency_schema.sql` + schema test

**Files:**
- Create: `src/main/resources/db/migration/V7__idempotency_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencySchemaTest.java`

**Interfaces:**
- Consumes: Flyway chain (V1–V6), `IntegrationTestBase` (container + `adminConnection()`).
- Produces: table `idempotency.idempotency_keys` with columns `id uuid pk default gen_random_uuid()`, `key text not null unique`, `request_fingerprint bytea not null`, `response_status int`, `response_content_type text`, `response_location text`, `response_body text`, `created_at timestamptz not null default now()`, `expires_at timestamptz not null`. Index `idempotency_keys_expires_at_idx` on `expires_at`. Grants for `nummus_app`: `select, insert, update, delete` (delete is new — the purge job needs it; no immutability story on this infra table).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class IdempotencySchemaTest extends IntegrationTestBase {

  @Test
  void schemaAcceptsRowsAndEnforcesKeyUniqueness() throws Exception {
    String key = "schema-" + java.util.UUID.randomUUID();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("""
          INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          VALUES ('%s', decode('00', 'hex'), now() + interval '24 hours')
          """.formatted(key));
      try (ResultSet rs = st.executeQuery(
          "SELECT response_status, response_body, created_at FROM idempotency.idempotency_keys WHERE key = '" + key + "'")) {
        assertTrue(rs.next());
        assertTrue(rs.getObject(1) == null);
        assertTrue(rs.getObject(2) == null);
        assertTrue(rs.getObject(3) != null);
      }
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate("""
          INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at)
          VALUES ('%s', decode('01', 'hex'), now() + interval '24 hours')
          """.formatted(key)));
      assertEquals("23505", duplicate.getSQLState());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencySchemaTest`
Expected: FAIL — `relation "idempotency.idempotency_keys" does not exist`.

- [ ] **Step 3: Write the migration**

```sql
-- M4 idempotency: stored responses for merchant-facing writes, so retries replay
-- instead of re-executing. Cross-cutting infrastructure owned by the shared
-- interfaces layer (no business module references this table). The response
-- columns are NULL until the owning transaction attaches the serialized
-- response on its way to commit — a committed row always carries its response.

create schema idempotency;

create table idempotency.idempotency_keys (
  id                     uuid primary key default gen_random_uuid(),
  key                    text not null unique,
  request_fingerprint    bytea not null,
  response_status        int,
  response_content_type  text,
  response_location      text,
  response_body          text,
  created_at             timestamptz not null default now(),
  expires_at             timestamptz not null
);

create index idempotency_keys_expires_at_idx on idempotency.idempotency_keys (expires_at);

-- Unlike the ledger schemas this table is mutable by design (attach, reclaim,
-- purge), so the app role gets the full row lifecycle.
grant usage on schema idempotency to nummus_app;
grant select, insert, update, delete on idempotency.idempotency_keys to nummus_app;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencySchemaTest`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V7__idempotency_schema.sql \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencySchemaTest.java
git commit -m "feat: add the idempotency_keys schema (V7)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: `IdempotencyStore` port + JDBC adapter (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/StoredResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/StoredRow.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyStore.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/infrastructure/JdbcClientIdempotencyStore.java`
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyStoreTest.java`

**Interfaces:**
- Consumes: V7 table (Task 1), `IntegrationTestBase`.
- Produces (used by Tasks 4–6):
  - `record StoredResponse(int status, String contentType, String location, String body)`
  - `record StoredRow(byte[] requestFingerprint, Instant expiresAt, StoredResponse response)` — `response` is null until attached.
  - `interface IdempotencyStore { void insert(String key, byte[] requestFingerprint, Instant expiresAt); Optional<StoredRow> findByKey(String key); boolean attachResponse(String key, StoredResponse response); boolean reclaimExpired(String key, byte[] newFingerprint, Instant newExpiresAt); int purgeExpired(Instant now); }`
  - Bean: `JdbcClientIdempotencyStore` (`@Repository`, constructor-injected `JdbcClient`). `insert` lets Postgres' unique index raise Spring's `DuplicateKeyException` — do not catch it here.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.interfaces.idempotency.StoredResponse;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyStoreTest extends IntegrationTestBase {

  @Autowired
  private IdempotencyStore store;

  private String freshKey() {
    return "store-" + UUID.randomUUID();
  }

  @Test
  void insertThenFindByKeyRoundTripsWithoutAResponse() {
    String key = freshKey();
    byte[] fingerprint = {1, 2, 3};
    Instant expiresAt = Instant.now().plusSeconds(900);
    assertThrows(DuplicateKeyException.class, () -> {
      store.insert(key, fingerprint, expiresAt);
      store.insert(key, new byte[] {9}, expiresAt);
    });
    Optional<StoredRow> found = store.findByKey(key);
    assertTrue(found.isPresent());
    assertArrayEquals(fingerprint, found.get().requestFingerprint());
    assertEquals(expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        found.get().expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    assertTrue(found.get().response() == null);
  }

  @Test
  void attachResponseStoresTheSerializedResponseOnce() {
    String key = freshKey();
    store.insert(key, new byte[] {1}, Instant.now().plusSeconds(900));
    assertTrue(store.attachResponse(key, new StoredResponse(201, "application/json", "/v1/payment-intents/x", "{\"a\":1}")));
    assertFalse(store.attachResponse(key, new StoredResponse(200, "application/json", null, "{\"a\":2}")));
    StoredRow row = store.findByKey(key).orElseThrow();
    assertEquals(201, row.response().status());
    assertEquals("/v1/payment-intents/x", row.response().location());
    assertEquals("{\"a\":1}", row.response().body());
  }

  @Test
  void reclaimExpiredClaimsOnlyExpiredSlotsAndClearsTheResponse() {
    String key = freshKey();
    store.insert(key, new byte[] {1}, Instant.now().minusSeconds(1));
    store.attachResponse(key, new StoredResponse(201, "application/json", null, "{}"));
    assertTrue(store.reclaimExpired(key, new byte[] {2}, Instant.now().plusSeconds(900)));
    StoredRow reclaimed = store.findByKey(key).orElseThrow();
    assertArrayEquals(new byte[] {2}, reclaimed.requestFingerprint());
    assertTrue(reclaimed.response() == null);
    // An unexpired slot refuses the reclaim.
    String fresh = freshKey();
    store.insert(fresh, new byte[] {1}, Instant.now().plusSeconds(900));
    assertFalse(store.reclaimExpired(fresh, new byte[] {2}, Instant.now().plusSeconds(900)));
  }

  @Test
  void purgeExpiredDeletesOnlyRowsPastTheirExpiry() {
    String old = freshKey();
    String kept = freshKey();
    store.insert(old, new byte[] {1}, Instant.now().minusSeconds(1));
    store.insert(kept, new byte[] {1}, Instant.now().plusSeconds(900));
    int purged = store.purgeExpired(Instant.now());
    assertTrue(purged >= 1);
    assertTrue(store.findByKey(old).isEmpty());
    assertTrue(store.findByKey(kept).isPresent());
  }
```

(Also add the missing static import `import static org.junit.jupiter.api.Assertions.assertThrows;`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyStoreTest`
Expected: compilation FAIL — `IdempotencyStore`/`StoredResponse`/`StoredRow` do not exist.

- [ ] **Step 3: Write the port and adapter**

`StoredResponse.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

/** A serialized first-execution response, replayed verbatim on retries. */
public record StoredResponse(int status, String contentType, String location, String body) {
}
```

`StoredRow.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;

/**
 * A key's stored state. {@code response} is null until the owning transaction
 * attaches it; a committed row always carries its response because the
 * reservation, the business write, and the attachment commit together.
 */
public record StoredRow(byte[] requestFingerprint, Instant expiresAt, StoredResponse response) {
}
```

`IdempotencyStore.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for the idempotency layer. {@link #insert} relies on the
 * unique index on {@code key} to lose races — losers receive Spring's
 * {@code DuplicateKeyException} and switch to the replay path.
 */
public interface IdempotencyStore {

  void insert(String key, byte[] requestFingerprint, Instant expiresAt);

  Optional<StoredRow> findByKey(String key);

  /** Attaches the response to a row that has none yet; false if one is already attached. */
  boolean attachResponse(String key, StoredResponse response);

  /**
   * Claims an expired slot for a new execution: rewrites the fingerprint and
   * expiry and clears any stale response. Returns false when the row is not
   * (or no longer) expired — someone else claimed it first.
   */
  boolean reclaimExpired(String key, byte[] newFingerprint, Instant newExpiresAt);

  /** Deletes every row past its expiry; returns the number of rows removed. */
  int purgeExpired(Instant now);
}
```

`infrastructure/JdbcClientIdempotencyStore.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency.infrastructure;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.interfaces.idempotency.StoredResponse;
import com.leandrossb.nummus.interfaces.idempotency.StoredRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientIdempotencyStore implements IdempotencyStore {

  private final JdbcClient jdbc;

  public JdbcClientIdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(String key, byte[] requestFingerprint, Instant expiresAt) {
    jdbc.sql("""
        insert into idempotency.idempotency_keys (key, request_fingerprint, expires_at)
        values (:key, :fingerprint, :expiresAt)
        """)
        .param("key", key)
        .param("fingerprint", requestFingerprint)
        .param("expiresAt", toOffsetDateTime(expiresAt))
        .update();
  }

  @Override
  public Optional<StoredRow> findByKey(String key) {
    return jdbc.sql("""
        select request_fingerprint, expires_at, response_status,
               response_content_type, response_location, response_body
        from idempotency.idempotency_keys where key = :key
        """)
        .param("key", key)
        .query((rs, i) -> mapRow(rs))
        .optional();
  }

  @Override
  public boolean attachResponse(String key, StoredResponse response) {
    return jdbc.sql("""
        update idempotency.idempotency_keys
        set response_status = :status, response_content_type = :contentType,
            response_location = :location, response_body = :body
        where key = :key and response_status is null
        """)
        .param("status", response.status())
        .param("contentType", response.contentType())
        .param("location", response.location())
        .param("body", response.body())
        .param("key", key)
        .update() == 1;
  }

  @Override
  public boolean reclaimExpired(String key, byte[] newFingerprint, Instant newExpiresAt) {
    return jdbc.sql("""
        update idempotency.idempotency_keys
        set request_fingerprint = :fingerprint, expires_at = :expiresAt,
            response_status = null, response_content_type = null,
            response_location = null, response_body = null
        where key = :key and expires_at <= now()
        """)
        .param("fingerprint", newFingerprint)
        .param("expiresAt", toOffsetDateTime(newExpiresAt))
        .param("key", key)
        .update() == 1;
  }

  @Override
  public int purgeExpired(Instant now) {
    return jdbc.sql("delete from idempotency.idempotency_keys where expires_at <= :now")
        .param("now", toOffsetDateTime(now))
        .update();
  }

  private static StoredRow mapRow(ResultSet rs) throws SQLException {
    StoredResponse response = rs.getObject(3) == null ? null
        : new StoredResponse(rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6));
    return new StoredRow(rs.getBytes(1), toInstant(rs.getObject(2, OffsetDateTime.class)), response);
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime timestamp) {
    return timestamp.toInstant();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyStoreTest`
Expected: PASS (4 tests). If `JdbcClient` rejects the `byte[]` param for `bytea`, register a `ByteArrayValueEncoder`... do NOT guess — stop and report.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/interfaces/idempotency/ \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencyStoreTest.java
git commit -m "feat: add the idempotency store port and JDBC adapter

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: `IdempotencyWebFilter` — header required, fail-closed (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/CachedBodyRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/RequestFingerprinter.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyWebFilter.java`
- Modify: `src/test/java/com/leandrossb/nummus/payments/PaymentsRestApiTest.java` (6 POST sites — lines ~39, 50, 141, 145, 149, 157)
- Modify: `src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java` (8 POST sites — lines ~41, 51, 75, 77, 79, 136, 152, 153)
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyRestApiTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase`, `@AutoConfigureMockMvc` (MockMvc executes registered filter beans — this task's test is the canary; if the 400s don't fire, STOP and report).
- Produces (used by Task 4):
  - `IdempotencyWebFilter.KEY_HEADER = "Idempotency-Key"`, `IdempotencyWebFilter.CACHED_BODY_ATTRIBUTE = "idempotency.cached-body"` (both `public static final String`).
  - `RequestFingerprinter.sha256(String method, String uri, byte[] body)` → `byte[][32]`.
  - Filter behavior: for `POST` under `/v1/**` — missing/blank/`length > 255` key → `400` problem+json rendered by the filter itself; otherwise the body is cached as a request attribute and the chain sees a re-readable wrapped request. Non-POST and non-`/v1` requests pass untouched.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IdempotencyRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  private static final String KEY = "Idempotency-Key";

  @Test
  void missingOrMalformedKeyIsRejectedOnEveryMerchantPost() throws Exception {
    // No header at all.
    mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
            .content("{\"holderName\":\"No Key Merchant\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    // Blank header.
    mockMvc.perform(post("/v1/accounts").header(KEY, "   ")
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Blank Key Merchant\"}"))
        .andExpect(status().isBadRequest());
    // Over 255 characters.
    mockMvc.perform(post("/v1/payment-intents").header(KEY, "k".repeat(256))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":1.0000}"))
        .andExpect(status().isBadRequest());
    // The transition endpoints are merchant writes too: a real account + no key.
    String accountId = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Valid Merchant\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getHeader("Location");
    mockMvc.perform(post(accountId + "/freeze")).andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/unfreeze")).andExpect(status().isBadRequest());
    mockMvc.perform(post(accountId + "/close")).andExpect(status().isBadRequest());
  }

  @Test
  void simulatorEndpointsDoNotRequireAKey() throws Exception {
    mockMvc.perform(post("/simulator/charges/" + UUID.randomUUID() + "/pay"))
        .andExpect(status().isNotFound()); // routed, key-free: 404 from the domain, not 400 from the filter
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyRestApiTest`
Expected: FAIL — the POSTs execute (201), no 400s.

- [ ] **Step 3: Write the filter, wrapper, and fingerprinter**

`CachedBodyRequest.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Re-readable request wrapper: the filter consumed the body to fingerprint it. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  CachedBodyRequest(HttpServletRequest request, byte[] body) {
    super(request);
    this.body = body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream buffer = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override public boolean isFinished() { return buffer.available() == 0; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
      }
      @Override public int read() { return buffer.read(); }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }
}
```

`RequestFingerprinter.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** SHA-256 over method, request URI, and raw body — the identity of a logical operation. */
public final class RequestFingerprinter {

  private RequestFingerprinter() {
  }

  public static byte[] sha256(String method, String uri, byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(method.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(uri.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      digest.update(body);
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
```

`IdempotencyWebFilter.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fails merchant writes closed: every POST under /v1 must carry a usable
 * Idempotency-Key. The path rule is defense in depth — @Idempotent is the real
 * mechanism, and a future merchant POST without it still gets a 400 here rather
 * than a silently non-idempotent write. Renders problem+json itself because a
 * filter runs outside the @ControllerAdvice's reach.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotencyWebFilter extends OncePerRequestFilter {

  public static final String KEY_HEADER = "Idempotency-Key";
  public static final String CACHED_BODY_ATTRIBUTE = "idempotency.cached-body";
  private static final int KEY_MAX_LENGTH = 255;

  private final ObjectMapper objectMapper;

  public IdempotencyWebFilter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    String key = request.getHeader(KEY_HEADER);
    if (key == null || key.isBlank() || key.length() > KEY_MAX_LENGTH) {
      ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "Idempotency-Key header (1-255 characters) is required on merchant writes");
      response.setStatus(HttpStatus.BAD_REQUEST.value());
      response.setContentType("application/problem+json");
      response.getWriter().write(objectMapper.writeValueAsString(problem));
      return;
    }
    byte[] body = request.getInputStream().readAllBytes();
    request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }
}
```

- [ ] **Step 4: Update the existing REST tests to send the header (same commit — they would otherwise fail)**

In `PaymentsRestApiTest.java`, six builders call `post("/v1/payment-intents")`. Append `.header("Idempotency-Key", UUID.randomUUID().toString())` to each — e.g. the helper at line 39 becomes:

```java
    MvcResult result = mockMvc.perform(post("/v1/payment-intents")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amountJson + "}"))
        .andExpect(status().isCreated())
        .andReturn();
    return result.getResponse().getHeader("Location");
```

Apply the identical one-line insertion to the other five sites (lines ~50, 141, 145, 149, 157 — every `post("/v1/payment-intents")` in the file). `java.util.UUID` is already imported.

In `AccountsRestApiTest.java`, eight builders call `post(...)`: `post("/v1/accounts")` at lines ~41, 51, 136, and `post(location + "/freeze")`, `"/unfreeze"`, `"/close"` at ~75, 77, 79, 152, 153. Same insertion, e.g.:

```java
    mockMvc.perform(post(location + "/freeze")
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
```

Add `import java.util.UUID;` to `AccountsRestApiTest` if absent. Unique random keys per call keep these tests semantically unchanged (no replay involved).

- [ ] **Step 5: Run the affected test classes**

Run: `<env-prefix> ./mvnw test -Dtest='IdempotencyRestApiTest,PaymentsRestApiTest,AccountsRestApiTest'`
Expected: PASS — the 2 new IdempotencyRestApiTest tests plus every pre-existing payments/accounts REST test (unchanged counts: no test methods added or removed in those files, only the header line).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/interfaces/idempotency/ \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencyRestApiTest.java \
  src/test/java/com/leandrossb/nummus/payments/PaymentsRestApiTest.java \
  src/test/java/com/leandrossb/nummus/accounts/AccountsRestApiTest.java
git commit -m "feat: require Idempotency-Key on merchant POSTs

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: `IdempotencyAspect` — reserve, execute, store, replay (TDD)

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-aop`)
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/Idempotent.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyKeyReuseException.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyProperties.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyAspect.java`
- Modify: `src/main/java/com/leandrossb/nummus/Application.java` (add `@ConfigurationPropertiesScan`)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java` (422 entry)
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java` (`@Idempotent` on create/freeze/unfreeze/close)
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java` (`@Idempotent` on create)
- Test: extend `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyRestApiTest.java`

**Interfaces:**
- Consumes: `IdempotencyStore`/`StoredRow`/`StoredResponse` (Task 2), `IdempotencyWebFilter.KEY_HEADER`/`CACHED_BODY_ATTRIBUTE` + `RequestFingerprinter.sha256` (Task 3), Spring's auto-configured `TransactionTemplate` (from `spring-boot-starter-jdbc`), `ObjectMapper`, `DuplicateKeyException`.
- Produces: `@Idempotent` (RUNTIME, METHOD); `IdempotencyKeyReuseException(String key)` → 422 via `GlobalExceptionHandler`; `IdempotencyProperties` record bound at `nummus.idempotency.ttl` (default 24h; used by Task 5's expiry test via property override). Replay responses carry header `Idempotency-Replayed: true` and the stored `Content-Type`/`Location`.

- [ ] **Step 1: Add the AOP starter to `pom.xml`** (after the `spring-boot-starter` dependency)

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
```

- [ ] **Step 2: Write the failing tests** (append to `IdempotencyRestApiTest`)

```java
  @Test
  void retryReplaysTheStoredResponseVerbatimWithoutReExecuting() throws Exception {
    String accountId = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Replay Merchant\"}"))
        .andReturn().getResponse().getHeader("Location");
    String body = "{\"accountId\":\"" + accountId.substring(accountId.lastIndexOf('/') + 1) + "\",\"amount\":12.5000}";
    String key = UUID.randomUUID().toString();

    var first = mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();
    var retry = mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();

    assertEquals(first.getResponse().getContentAsString(), retry.getResponse().getContentAsString());
    assertEquals(first.getResponse().getHeader("Location"), retry.getResponse().getHeader("Location"));
    assertTrue(first.getResponse().getHeader("Idempotency-Replayed") == null);
    assertEquals("true", retry.getResponse().getHeader("Idempotency-Replayed"));

    // No double execution: exactly one intent exists for this account.
    try (var c = adminConnection(); var st = c.createStatement()) {
      try (var rs = st.executeQuery(
          "SELECT count(*) FROM payments.payment_intent WHERE account_public_id = '"
              + accountId.substring(accountId.lastIndexOf('/') + 1) + "'")) {
        rs.next();
        assertEquals(1, rs.getInt(1));
      }
    }
  }

  @Test
  void sameKeyWithADifferentRequestIsRejected() throws Exception {
    String key = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/accounts").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"First Op\"}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/accounts").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Second Op\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.status").value(422));
  }

  @Test
  void accountTransitionsReplayTheirStored200() throws Exception {
    String key = UUID.randomUUID().toString();
    String location = mockMvc.perform(post("/v1/accounts").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Freeze Replay\"}"))
        .andReturn().getResponse().getHeader("Location");
    mockMvc.perform(post(location + "/freeze").header(KEY, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    // Re-freezing for real would be a 409 (account not ACTIVE) — the replay
    // must return the stored 200 instead.
    mockMvc.perform(post(location + "/freeze").header(KEY, key))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"))
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }

  @Test
  void domainErrorsAreNotStoredAndRerunDeterministically() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = "{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":5.0000}";
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/v1/payment-intents").header(KEY, key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound()); // re-executed, same deterministic 404 — no replay header
  }
```

Add to the class's static imports: `header`, and `assertEquals`/`assertTrue` from JUnit (`import static org.junit.jupiter.api.Assertions.assertEquals; import static org.junit.jupiter.api.Assertions.assertTrue;` plus `import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;`).

- [ ] **Step 3: Run tests to verify they fail**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyRestApiTest`
Expected: FAIL — the retry test gets a second distinct 201 (no aspect yet); the 422 test gets 201.

- [ ] **Step 4: Write the annotation, exception, properties, aspect; annotate the endpoints; extend the handler**

`Idempotent.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a merchant-facing write as idempotent: the Idempotency-Key reserves a
 * slot in the same transaction as the handler's writes, and the serialized
 * response is attached before commit — retries replay instead of re-executing.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
```

`IdempotencyKeyReuseException.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

/** An Idempotency-Key was reused for a different request. Keys identify one logical operation. */
public class IdempotencyKeyReuseException extends RuntimeException {

  public IdempotencyKeyReuseException(String key) {
    super("Idempotency-Key '" + key + "' was already used with a different request");
  }
}
```

`IdempotencyProperties.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Retention window for stored responses. After expiry a key is reclaimed and re-executed. */
public record IdempotencyProperties(@DefaultValue("24h") Duration ttl) {
}
```

`IdempotencyAspect.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Reserve → execute → store, all in one transaction, so a committed business
 * write always has its stored response and a rolled-back one leaves nothing
 * behind. The transaction wraps only the handler method: domain exceptions
 * propagate, roll everything back, and are rendered by the advice outside this
 * layer — their retries re-execute deterministically.
 */
@Aspect
@Component
public class IdempotencyAspect {

  private final IdempotencyStore store;
  private final TransactionTemplate transactions;
  private final ObjectMapper objectMapper;
  private final IdempotencyProperties properties;

  public IdempotencyAspect(IdempotencyStore store, TransactionTemplate transactions,
      ObjectMapper objectMapper, IdempotencyProperties properties) {
    this.store = store;
    this.transactions = transactions;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  @Around("@annotation(com.leandrossb.nummus.interfaces.idempotency.Idempotent)")
  public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    HttpServletRequest request = currentRequest();
    String key = request.getHeader(IdempotencyWebFilter.KEY_HEADER);
    byte[] fingerprint = RequestFingerprinter.sha256(request.getMethod(), request.getRequestURI(),
        (byte[]) request.getAttribute(IdempotencyWebFilter.CACHED_BODY_ATTRIBUTE));
    Instant expiresAt = Instant.now().plus(properties.ttl());
    try {
      return transactions.execute(txStatus -> {
        store.insert(key, fingerprint, expiresAt);
        return proceedAndAttach(joinPoint, key);
      });
    } catch (DuplicateKeyException raced) {
      return raced(joinPoint, key, fingerprint, expiresAt, raced);
    }
  }

  /** Runs the handler and attaches the serialized response — call inside an open transaction. */
  private Object proceedAndAttach(ProceedingJoinPoint joinPoint, String key) {
    Object result;
    try {
      result = joinPoint.proceed();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
    store.attachResponse(key, toStoredResponse(result));
    return result;
  }

  /**
   * The unique index lost us the slot. The winner's commit made its row visible
   * with its response attached; if the winner rolled back, our insert above
   * would have succeeded and we would not be here.
   */
  private Object raced(ProceedingJoinPoint joinPoint, String key, byte[] fingerprint,
      Instant expiresAt, DuplicateKeyException raced) throws Throwable {
    var row = store.findByKey(key).orElseThrow(() -> raced);
    boolean expired = !row.expiresAt().isAfter(Instant.now());
    if (row.response() == null || expired) {
      // An expired slot is free real estate: claim it and run as new. Losing
      // the reclaim race means a concurrent request claimed it — treat as reuse.
      if (!store.reclaimExpired(key, fingerprint, expiresAt)) {
        throw new IdempotencyKeyReuseException(key);
      }
      return transactions.execute(txStatus -> proceedAndAttach(joinPoint, key));
    }
    if (!Arrays.equals(row.requestFingerprint(), fingerprint)) {
      throw new IdempotencyKeyReuseException(key);
    }
    return replay(row.response());
  }

  private Object replay(StoredResponse stored) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
        .header("Idempotency-Replayed", "true");
    if (stored.contentType() != null) {
      builder.contentType(MediaType.parseMediaType(stored.contentType()));
    }
    if (stored.location() != null) {
      builder.location(URI.create(stored.location()));
    }
    return builder.body(stored.body());
  }

  private StoredResponse toStoredResponse(Object result) {
    try {
      if (result instanceof ResponseEntity<?> entity) {
        String body = entity.getBody() == null ? null : objectMapper.writeValueAsString(entity.getBody());
        MediaType contentType = entity.getHeaders().getContentType();
        URI location = entity.getHeaders().getLocation();
        return new StoredResponse(entity.getStatusCode().value(),
            contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType.toString(),
            location == null ? null : location.toString(), body);
      }
      return new StoredResponse(200, MediaType.APPLICATION_JSON_VALUE, null,
          objectMapper.writeValueAsString(result));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize idempotent response", e);
    }
  }

  private static HttpServletRequest currentRequest() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
  }
}
```

`Application.java` — add the scan so `IdempotencyProperties` becomes a bean:
```java
package com.leandrossb.nummus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
```

`GlobalExceptionHandler.java` — add the entry (import `com.leandrossb.nummus.interfaces.idempotency.IdempotencyKeyReuseException`, place after `conflict`):
```java
  @ExceptionHandler(IdempotencyKeyReuseException.class)
  ProblemDetail idempotencyReuse(IdempotencyKeyReuseException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }
```

`AccountsController.java` — annotate the four writes (`import com.leandrossb.nummus.interfaces.idempotency.Idempotent;`; each method gains one line):
```java
  @Idempotent
  @PostMapping
  ResponseEntity<AccountResponse> create(@Valid @RequestBody OpenAccountRequest request) {
    ...
  }

  @Idempotent
  @PostMapping("/{id}/freeze")
  AccountResponse freeze(@PathVariable UUID id) {
    ...
  }
```
(`unfreeze` and `close` identically.)

`PaymentsController.java` — same one-liner on `create`:
```java
  @Idempotent
  @PostMapping
  ResponseEntity<IntentResponse> create(@Valid @RequestBody CreateIntentRequest request) {
    ...
  }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyRestApiTest`
Expected: PASS (6 tests — the 2 from Task 3 plus these 4).
Then the full suite: `<env-prefix> ./mvnw verify` → green, 129 tests.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/idempotency/
git commit -m "feat: replay stored responses for idempotent merchant writes

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: Expiry reclaim + purge job (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyPurgeJob.java`
- Modify: `src/main/java/com/leandrossb/nummus/Application.java` (`@EnableScheduling`)
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyExpiryTest.java`
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyPurgeTest.java`

**Interfaces:**
- Consumes: `IdempotencyStore` (Task 2), `IdempotencyProperties.ttl` override (Task 4), aspect reclaim path (Task 4).
- Produces: `IdempotencyPurgeJob` bean with `@Scheduled public void purge()` delegating to `store.purgeExpired(Instant.now())` (hourly, 1h initial delay so it never fires mid-test).

- [ ] **Step 1: Write the failing tests**

`IdempotencyExpiryTest.java`:
```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Owns a Spring context with a zero TTL, so every stored response is born expired. */
@SpringBootTest(properties = "nummus.idempotency.ttl=PT0S")
@AutoConfigureMockMvc
class IdempotencyExpiryTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void expiredKeyIsReclaimedAndReexecutedAsNew() throws Exception {
    String accountId = JsonPath.read(mockMvc.perform(post("/v1/accounts")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Expiry Merchant\"}"))
        .andReturn().getResponse().getContentAsString(), "$.publicId");
    String body = "{\"accountId\":\"" + accountId + "\",\"amount\":7.0000}";
    String key = UUID.randomUUID().toString();

    String first = mockMvc.perform(post("/v1/payment-intents").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    var second = mockMvc.perform(post("/v1/payment-intents").header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();

    // Reclaimed slot → new execution → a different intent, and no replay header.
    assertNull(second.getResponse().getHeader("Idempotency-Replayed"));
    assertNotEquals(JsonPath.read(first, "$.publicId"),
        JsonPath.read(second.getResponse().getContentAsString(), "$.publicId"));
  }
}
```

`IdempotencyPurgeTest.java`:
```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.interfaces.idempotency.IdempotencyPurgeJob;
import com.leandrossb.nummus.interfaces.idempotency.IdempotencyStore;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotencyPurgeTest extends IntegrationTestBase {

  @Autowired
  private IdempotencyStore store;

  @Autowired
  private IdempotencyPurgeJob job;

  @Test
  void purgeRemovesOnlyExpiredRows() {
    String expired = "purge-" + UUID.randomUUID();
    String alive = "purge-" + UUID.randomUUID();
    store.insert(expired, new byte[] {1}, Instant.now().minusSeconds(1));
    store.insert(alive, new byte[] {1}, Instant.now().plusSeconds(3600));

    job.purge();

    assertTrue(store.findByKey(expired).isEmpty());
    assertFalse(store.findByKey(alive).isEmpty());
  }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `<env-prefix> ./mvnw test -Dtest='IdempotencyExpiryTest,IdempotencyPurgeTest'`
Expected: compilation FAIL — `IdempotencyPurgeJob` does not exist; expiry test would also fail (retry replays instead of reclaiming — the reclaim branch exists but TTL is 24h in the shared context... the class-specific `PT0S` property is what makes it behave).

- [ ] **Step 3: Implement**

`IdempotencyPurgeJob.java`:
```java
package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly sweep of expired stored responses; reclaims already handle correctness, this bounds the table. */
@Component
public class IdempotencyPurgeJob {

  private final IdempotencyStore store;

  public IdempotencyPurgeJob(IdempotencyStore store) {
    this.store = store;
  }

  @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
  public void purge() {
    store.purgeExpired(Instant.now());
  }
}
```

`Application.java` — add `@EnableScheduling` (import `org.springframework.scheduling.annotation.EnableScheduling`) next to `@ConfigurationPropertiesScan`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `<env-prefix> ./mvnw test -Dtest='IdempotencyExpiryTest,IdempotencyPurgeTest'`
Expected: PASS (2 tests). Note: the `PT0S` property creates a second Spring context — expect a slower first run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyPurgeJob.java \
  src/main/java/com/leandrossb/nummus/Application.java \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencyExpiryTest.java \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencyPurgeTest.java
git commit -m "feat: expire and purge idempotency keys after the retention window

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: Exactly-once execution proof under concurrency

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyConcurrencyTest.java`

**Interfaces:**
- Consumes: full M4 pipeline (Tasks 1–4), the M3 concurrency-test idiom (`ExecutorService`, `CountDownLatch`, per-future timeouts, `@Timeout`).

- [ ] **Step 1: Write the test**

```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class IdempotencyConcurrencyTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  @Timeout(120)
  void racingIdenticalPostsExecuteOnceAndReplayTheSameResponse() throws Exception {
    String accountId = JsonPath.read(mockMvc.perform(post("/v1/accounts")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Race Key Merchant\"}"))
        .andReturn().getResponse().getContentAsString(), "$.publicId");
    String key = UUID.randomUUID().toString();
    String body = "{\"accountId\":\"" + accountId + "\",\"amount\":3.0000}";

    int workers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<MvcResult>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return mockMvc.perform(post("/v1/payment-intents").header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON).content(body))
              .andReturn();
        }));
      }
      start.countDown();
      for (Future<MvcResult> future : futures) {
        future.get(90, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    int created = 0;
    int replayed = 0;
    String referenceBody = null;
    for (Future<MvcResult> future : futures) {
      MvcResult result = future.get(1, TimeUnit.SECONDS);
      assertEquals(201, result.getResponse().getStatus());
      if (Boolean.parseBoolean(result.getResponse().getHeader("Idempotency-Replayed"))) {
        replayed++;
      } else {
        created++;
      }
      if (referenceBody == null) {
        referenceBody = result.getResponse().getContentAsString();
      } else {
        assertEquals(referenceBody, result.getResponse().getContentAsString());
      }
    }
    assertEquals(1, created);
    assertEquals(workers - 1, replayed);

    // Ground truth: one intent row for the account.
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM payments.payment_intent WHERE account_public_id = '"
            + accountId + "'")) {
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
  }
}
```

- [ ] **Step 2: Run the test**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyConcurrencyTest`
Expected: PASS (1 test). If it fails with lock-wait timeouts, do NOT add lock_timeout — stop and report (the unique-index wait is the designed in-flight backpressure).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/idempotency/IdempotencyConcurrencyTest.java
git commit -m "test: prove exactly-once execution per idempotency key

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Amount magnitude bound (M3-review backlog item)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreateIntentRequest.java`
- Test: append to `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyRestApiTest.java`

**Interfaces:**
- Consumes: bean validation on `CreateIntentRequest` (validation 400s bypass the aspect — argument resolution happens before the advised method runs).
- Produces: amounts beyond `numeric(19,4)` fail with a clean 400 field message instead of a 500 at INSERT.

- [ ] **Step 1: Write the failing test**

```java
  @Test
  void amountsBeyondTheColumnBoundsAreRejectedAs400() throws Exception {
    mockMvc.perform(post("/v1/payment-intents").header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":10000000000000000.0000}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("amount must fit numeric(19,4): at most 15 integer and 4 fraction digits"));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyRestApiTest`
Expected: FAIL — 500 (INSERT overflows `numeric(19,4)`).

- [ ] **Step 3: Add the constraint**

```java
package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for creating a payment intent. Scale is validated by Money (400 on breach). */
public record CreateIntentRequest(
    @NotNull(message = "accountId must not be null") UUID accountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    @Digits(integer = 15, fraction = 4,
        message = "amount must fit numeric(19,4): at most 15 integer and 4 fraction digits") BigDecimal amount,
    @Min(value = 60, message = "expiresInSeconds must be at least 60")
    @Max(value = 86400, message = "expiresInSeconds must be at most 86400") Long expiresInSeconds) {
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `<env-prefix> ./mvnw test -Dtest=IdempotencyRestApiTest`
Expected: PASS (7 tests in the class).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreateIntentRequest.java \
  src/test/java/com/leandrossb/nummus/idempotency/IdempotencyRestApiTest.java
git commit -m "fix: reject amounts beyond numeric(19,4) with a 400 at the boundary

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md`
- Modify: `docs/m2-backlog.md`

- [ ] **Step 1: Full verify**

Run: `<env-prefix> ./mvnw verify`
Expected: `BUILD SUCCESS`, all tests green — **133 expected** (118 before M4, +1 schema, +4 store, +2 filter, +4 replay, +2 expiry/purge, +1 concurrency, +1 amount-bound).

- [ ] **Step 2: Update `README.md`**

```markdown
- [x] M4 — Idempotency layer for merchant APIs
```

- [ ] **Step 3: Update `docs/m2-backlog.md`**

In "From the M2 review": strike the amount-magnitude bullet (implemented in M4 as `@Digits`), and append an honest note:

```markdown
M4 added the idempotency layer: stored responses replay verbatim, 2xx only —
4xx paths roll back and re-execute deterministically, which is observationally
equivalent to replay but is not storage; recorded here so nobody "fixes" the
error paths into storage without revisiting the `UnexpectedRollbackException`
hazard documented in the M4 spec.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/m2-backlog.md
git commit -m "docs: mark M4 idempotency layer complete

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

- [ ] **Step 5: Report**

Report the final `./mvnw verify` summary line and test count verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V7 schema + grants | 1 |
| Store port + adapter (insert/find/attach/reclaim/purge) | 2 |
| Filter: header required, fail-closed on POST /v1/**, body caching | 3 |
| Aspect: atomic reserve/execute/store, replay + `Idempotency-Replayed` | 4 |
| 422 on fingerprint mismatch (`IdempotencyKeyReuseException`) | 4 |
| Domain errors not stored, re-execute deterministically | 4 |
| TTL property, expiry reclaim, hourly purge, `@EnableScheduling` | 5 |
| Exactly-once execution per key (concurrency proof) | 6 |
| Amount `@Digits` bound (backlog item) | 7 |
| Success criteria, README, backlog | 8 |
