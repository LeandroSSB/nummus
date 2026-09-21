# M11 — API Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-tenant rate limiting on authenticated routes, a request-body cap on the buffered merchant-write path, and API-key lifecycle (expiry, `last_used_at`, rotation with grace) for merchant and operator keys.

**Architecture:** A token-bucket `RateLimitFilter` sits between the auth filter and the idempotency filter (`interfaces.ratelimit`, in-memory, config-driven). Key lifecycle extends the existing `merchants` module: one V14 migration adds `expires_at`/`last_used_at` to both key tables (dual-purpose `expires_at` = mint-time lifetime and rotation grace), the store enforces expiry in the auth lookups and stamps `last_used_at` best-effort, and two self-serve rotation routes mint a replacement and retire the calling key at `least(existing, now + grace)`. The body cap lands inside `IdempotencyWebFilter` — the only unbounded `readAllBytes` on the path.

**Tech Stack:** Java 25, Spring Boot (servlet filters, `@ConfigurationProperties` records with `@DefaultValue`), PostgreSQL via `JdbcClient` + Flyway, JUnit 5 + Testcontainers (`IntegrationTestBase`), MockMvc.

**Spec:** `docs/superpowers/specs/2026-09-21-m11-api-hardening-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`); every commit message ends with the trailer `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing anywhere.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused test run (replace `<Test>` and the branch is `worktree-m11-api-hardening`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m11-api-hardening origin/worktree-m11-api-hardening && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Test>'
  ```
  Full verify is the same command with `./mvnw -B verify` instead of the `-Dtest` form. Every task: **push first**, then run remotely.
- **TDD strictly:** write the failing test, see it fail remotely (RED), implement, see it pass remotely (GREEN), commit.
- **No new dependencies.** Token bucket is hand-rolled; the fractional token math uses `double` deliberately (rate math, not money — the `BigDecimal` rule is about money).
- **Existing suites must never hit the limiter:** default capacities (merchant 600 / operator 120) are sized so shared-context test classes stay far below exhaustion. Do not lower defaults.
- **Worktree:** execution starts from a worktree on branch `worktree-m11-api-hardening` (created via the using-git-worktrees flow at execution time). Never commit on `main`.
- Baseline: **262 tests, all green** on `main` at plan-writing time. Running totals after each task are stated in each task's GREEN step.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V14__api_key_lifecycle.sql            (new, Task 1)
src/main/java/com/leandrossb/nummus/interfaces/ratelimit/TokenBucket.java        (new, Task 2)
src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitProperties.java (new, Task 3)
src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitFilter.java     (new, Task 3)
src/main/java/com/leandrossb/nummus/merchants/application/ResolvedMerchantKey.java (new, Task 4)
src/main/java/com/leandrossb/nummus/merchants/application/InvalidKeyExpiryException.java (new, Task 5)
src/main/java/com/leandrossb/nummus/merchants/application/ApiKeyProperties.java    (new, Task 6)
src/main/java/com/leandrossb/nummus/merchants/application/RotatedApiKey.java       (new, Task 6)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateKeyRequest.java (new, Task 5)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyRequest.java (new, Task 6)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyResponse.java (new, Task 6)
src/main/java/com/leandrossb/nummus/interfaces/HttpProperties.java   (new, Task 7)

src/main/java/com/leandrossb/nummus/merchants/domain/ApiKey.java               (modify, Tasks 4/5)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java   (modify, Tasks 4/5/6)
src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java (modify, 4/5/6)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java (modify, Task 4)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantsServiceImpl.java (modify, Tasks 4/5)
src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysService.java  (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysServiceImpl.java (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysService.java (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysServiceImpl.java (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/interfaces/MeController.java     (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorKeysController.java (modify, Tasks 5/6)
src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java (modify, Task 5 — insertApiKey arity only)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateKeyResponse.java (modify, Task 5)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/ApiKeyResponse.java (modify, Task 4)
src/main/java/com/leandrossb/nummus/interfaces/auth/AuthenticatedMerchant.java (modify, Task 4)
src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthentication.java (modify, Task 4)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java     (modify, Task 5)
src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyWebFilter.java (modify, Task 7)

src/test/java/com/leandrossb/nummus/merchants/ApiKeyLifecycleSchemaTest.java  (new, Task 1)
src/test/java/com/leandrossb/nummus/interfaces/ratelimit/TokenBucketTest.java (new, Task 2)
src/test/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitFilterTest.java (new, Task 3)
src/test/java/com/leandrossb/nummus/merchants/ApiKeyExpiryAuthTest.java       (new, Task 4)
src/test/java/com/leandrossb/nummus/merchants/KeyExpiryRestApiTest.java       (new, Task 5)
src/test/java/com/leandrossb/nummus/merchants/KeyRotationRestApiTest.java     (new, Task 6)
src/test/java/com/leandrossb/nummus/idempotency/RequestBodyCapTest.java       (new, Task 7, plain unit)
src/test/java/com/leandrossb/nummus/idempotency/RequestBodyCapRestTest.java   (new, Task 7, REST)
src/test/java/com/leandrossb/nummus/merchants/MerchantStoreTest.java          (modify, Task 4)
src/test/java/com/leandrossb/nummus/merchants/application/FakeMerchantsService.java (modify, Task 4)
README.md, docs/m2-backlog.md                                                (modify, Task 8)
```

---

### Task 1: `V14` — key lifecycle columns

**Files:**
- Test: `src/test/java/com/leandrossb/nummus/merchants/ApiKeyLifecycleSchemaTest.java`
- Create: `src/main/resources/db/migration/V14__api_key_lifecycle.sql`

**Interfaces:**
- Produces: columns `merchants.api_key.expires_at`, `merchants.api_key.last_used_at`, `merchants.operator_key.expires_at`, `merchants.operator_key.last_used_at` — all `timestamptz null`, no default. Tasks 4–6 depend on them.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class ApiKeyLifecycleSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void lifecycleColumnsExistAndStayNullByDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      String merchantId = UUID.randomUUID().toString();
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'lifecycle probe')");
      st.executeUpdate("insert into merchants.api_key (merchant_id, key_hash, prefix) "
          + "select id, 'lifecycle-hash-1', 'nummus_s' from merchants.merchant "
          + "where public_id = '" + merchantId + "'");
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix) "
          + "values ('lifecycle-hash-2', 'nummus_s')");

      try (ResultSet rs = st.executeQuery(
          "select expires_at, last_used_at from merchants.api_key where key_hash = 'lifecycle-hash-1'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("expires_at"));
        assertEquals(null, rs.getObject("last_used_at"));
      }
      try (ResultSet rs = st.executeQuery(
          "select expires_at, last_used_at from merchants.operator_key where key_hash = 'lifecycle-hash-2'")) {
        assertTrue(rs.next());
        assertEquals(null, rs.getObject("expires_at"));
        assertEquals(null, rs.getObject("last_used_at"));
      }
    }
  }

  @Test
  void expiresAtComparesAndLastUsedAtUpdates() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix, expires_at) "
          + "values ('lifecycle-hash-3', 'nummus_s', now() - interval '1 hour')");
      try (ResultSet rs = st.executeQuery("select expires_at < now() as already_past "
          + "from merchants.operator_key where key_hash = 'lifecycle-hash-3'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("already_past"));
      }
      assertEquals(1, st.executeUpdate("update merchants.operator_key set last_used_at = now() "
          + "where key_hash = 'lifecycle-hash-3'"));
      try (ResultSet rs = st.executeQuery("select last_used_at is not null as stamped "
          + "from merchants.operator_key where key_hash = 'lifecycle-hash-3'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("stamped"));
      }
    }
  }

  @Test
  void appRoleUpdatesTheLifecycleColumnsWithoutNewGrants() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'grant probe')");
      st.executeUpdate("insert into merchants.api_key (merchant_id, key_hash, prefix) "
          + "select id, 'lifecycle-hash-4', 'nummus_s' from merchants.merchant "
          + "where public_id = '" + merchantId + "'");
    }
    // V10/V11 already grant UPDATE on both key tables; V14 adds no grants.
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("update merchants.api_key set expires_at = now(), "
          + "last_used_at = now() where key_hash = 'lifecycle-hash-4'"));
      assertEquals(1, st.executeUpdate("update merchants.operator_key set expires_at = now(), "
          + "last_used_at = now() where key_hash = 'lifecycle-hash-2'"));
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push the worktree branch; remote `test -Dtest=ApiKeyLifecycleSchemaTest` → FAIL (`column "expires_at" does not exist`).

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V14__api_key_lifecycle.sql`:

```sql
-- M11 API hardening: key lifecycle. expires_at is dual-purpose — the
-- operator-chosen lifetime at mint (null = never) and the rotation grace
-- end (least() of the two). last_used_at is stamped best-effort on every
-- successful authentication; it never gates authentication.

alter table merchants.api_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;

alter table merchants.operator_key
  add column expires_at   timestamptz null,
  add column last_used_at timestamptz null;
```

No grant changes — V10/V11 already grant `update` on both tables to `nummus_app`.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=ApiKeyLifecycleSchemaTest` → 3/3; full `verify` → BUILD SUCCESS, **265 tests** (262 + 3).

- [ ] **Step 5: Commit** — `feat: add the api key lifecycle columns (V14)` + trailer; push.

---

### Task 2: `TokenBucket` — pure rate math (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/ratelimit/TokenBucket.java`
- Test: `src/test/java/com/leandrossb/nummus/interfaces/ratelimit/TokenBucketTest.java`

**Interfaces:**
- Produces: `TokenBucket(int capacity, int refillPerSecond, long nowNanos)`; `synchronized boolean tryConsume(long nowNanos)`; `synchronized int retryAfterSeconds(long nowNanos)`. Time enters as a caller-supplied monotonic nanosecond stamp (`System.nanoTime()` in the filter, raw values in tests) — deterministic without a Clock abstraction. Task 3 consumes these.

- [ ] **Step 1: Write the failing test** (plain JUnit, no Spring)

```java
package com.leandrossb.nummus.interfaces.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

  private static final long SECOND = 1_000_000_000L;

  @Test
  void consumesUpToCapacityThenRefuses() {
    TokenBucket bucket = new TokenBucket(2, 1, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    assertFalse(bucket.tryConsume(0));
  }

  @Test
  void refillAccruesOverElapsedTime() {
    TokenBucket bucket = new TokenBucket(2, 1, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    assertFalse(bucket.tryConsume(0));
    // One second later exactly one token has accrued.
    assertTrue(bucket.tryConsume(SECOND));
    assertFalse(bucket.tryConsume(SECOND));
  }

  @Test
  void tokensClampAtCapacity() {
    TokenBucket bucket = new TokenBucket(2, 1000, 0);
    assertTrue(bucket.tryConsume(0));
    assertTrue(bucket.tryConsume(0));
    // An hour at 1000/s refills far beyond capacity; only two tokens exist.
    long hour = 3600 * SECOND;
    assertTrue(bucket.tryConsume(hour));
    assertTrue(bucket.tryConsume(hour));
    assertFalse(bucket.tryConsume(hour));
  }

  @Test
  void subTokenElapsedDoesNotConsume() {
    TokenBucket bucket = new TokenBucket(1, 1, 0);
    assertTrue(bucket.tryConsume(0));
    // 100ms accrues 0.1 tokens — not enough.
    assertFalse(bucket.tryConsume(SECOND / 10));
  }

  @Test
  void retryAfterSecondsCoversTheNextRefill() {
    TokenBucket bucket = new TokenBucket(1, 2, 0);
    assertTrue(bucket.tryConsume(0));
    // Empty at 2/s: the next token lands in 0.5s; the header rounds up to 1.
    assertTrue(bucket.retryAfterSeconds(0) >= 1);
    // Half a second later one token exists; tryConsume wins it and the
    // following refill estimate is again a whole second at most.
    assertTrue(bucket.tryConsume(SECOND / 2));
    assertTrue(bucket.retryAfterSeconds(SECOND / 2) >= 1);
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote compilation FAIL (type `TokenBucket` absent).

- [ ] **Step 3: Implement**

`src/main/java/com/leandrossb/nummus/interfaces/ratelimit/TokenBucket.java`:

```java
package com.leandrossb.nummus.interfaces.ratelimit;

/**
 * Classic token bucket for per-tenant request throttling: bursts up to
 * {@code capacity}, refilled at {@code refillPerSecond} tokens per second,
 * computed lazily from the caller-supplied monotonic nanosecond stamp — no
 * background threads. The fractional token arithmetic is rate math, not
 * money; double is deliberate (the BigDecimal rule is about money).
 */
final class TokenBucket {

  private final int capacity;
  private final int refillPerSecond;
  private double tokens;
  private long lastRefillNanos;

  TokenBucket(int capacity, int refillPerSecond, long nowNanos) {
    this.capacity = capacity;
    this.refillPerSecond = refillPerSecond;
    this.tokens = capacity;
    this.lastRefillNanos = nowNanos;
  }

  /** Consumes one token if a full token is available. */
  synchronized boolean tryConsume(long nowNanos) {
    refill(nowNanos);
    if (tokens >= 1.0) {
      tokens -= 1.0;
      return true;
    }
    return false;
  }

  /** Whole seconds until at least one full token is available; always at least 1. */
  synchronized int retryAfterSeconds(long nowNanos) {
    refill(nowNanos);
    double deficit = 1.0 - tokens;
    if (deficit <= 0.0) {
      return 1;
    }
    return Math.max(1, (int) Math.ceil(deficit / refillPerSecond));
  }

  private void refill(long nowNanos) {
    if (nowNanos <= lastRefillNanos) {
      return;
    }
    double added = (nowNanos - lastRefillNanos) / 1_000_000_000.0 * refillPerSecond;
    tokens = Math.min(capacity, tokens + added);
    lastRefillNanos = nowNanos;
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=TokenBucketTest` → 5/5; full `verify` → BUILD SUCCESS, **270 tests** (265 + 5).

- [ ] **Step 5: Commit** — `feat: add the per-tenant token bucket` + trailer; push.

---

### Task 3: `RateLimitFilter` + properties (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitProperties.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitFilter.java`
- Test: `src/test/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: `TokenBucket` (Task 2); `MerchantAuthFilter.MERCHANT_ATTRIBUTE` / `OPERATOR_ATTRIBUTE` request attributes; `AuthenticatedMerchant(UUID merchantPublicId, String name)` and `AuthenticatedOperator(UUID keyPublicId)` (current shapes).
- Produces: HTTP filter at `@Order(Ordered.HIGHEST_PRECEDENCE + 500)` — between `MerchantAuthFilter` (`+0`) and `IdempotencyWebFilter` (`+1000`). Properties `nummus.ratelimit.merchant-capacity` (600), `merchant-refill-per-second` (10), `operator-capacity` (120), `operator-refill-per-second` (2). 429 renders `application/problem+json` with a `Retry-After` header and stores no idempotency row.

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.interfaces.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Tight buckets per test class; every test mints fresh merchants/keys, so
 *  each gets a fresh bucket and tests cannot interfere. */
@AutoConfigureMockMvc
class RateLimitFilterTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void tightBuckets(DynamicPropertyRegistry registry) {
    registry.add("nummus.ratelimit.merchant-capacity", () -> "2");
    registry.add("nummus.ratelimit.merchant-refill-per-second", () -> "200");
    registry.add("nummus.ratelimit.operator-capacity", () -> "3");
    registry.add("nummus.ratelimit.operator-refill-per-second", () -> "200");
  }

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

  @Test
  void merchantBucketRejectsWith429AndRetryAfter() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Limited Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(429));
  }

  @Test
  void bucketRefillsOverTime() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Refill Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isTooManyRequests());
    // At 200 tokens/s, ~60ms restores well over one token.
    Thread.sleep(60);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
  }

  @Test
  void merchantsHaveIsolatedBuckets() throws Exception {
    String a = "Bearer " + createMerchantAndGetKey("Isolated A");
    String b = "Bearer " + createMerchantAndGetKey("Isolated B");
    mockMvc.perform(get("/v1/me").header("Authorization", a)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", a)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", a))
        .andExpect(status().isTooManyRequests());
    // B's bucket is untouched.
    mockMvc.perform(get("/v1/me").header("Authorization", b)).andExpect(status().isOk());
  }

  @Test
  void operatorBucketRejectsIndependently() throws Exception {
    String operator = operatorAuth();
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/v1/merchants")
              .header("Authorization", operator)
              .header(KEY, UUID.randomUUID().toString())
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"Operator Burst " + i + "\"}"))
          .andExpect(status().isCreated());
    }
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operator)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Operator Over\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  void unauthenticatedPassesThroughAndA429StoresNoIdempotencyRow() throws Exception {
    // No bearer: repeated 401s — the 401 path is never throttled.
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized());
    }
    String idemKey = UUID.randomUUID().toString();
    String bearer = "Bearer " + createMerchantAndGetKey("Rowless Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    // The rate-limited POST is refused pre-controller: no idempotency row.
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isTooManyRequests());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "select count(*) from idempotency.idempotency_keys where key = '" + idemKey + "'")) {
      org.junit.jupiter.api.Assertions.assertTrue(rs.next());
      org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(1));
    }
  }
}
```

Note: `operatorKeys.create(null)` — the two-arg overload arrives in Task 5; until then this test uses the existing `operatorKeys.create()` (no argument). Task 5 updates this call site together with the service signature. **Write Step 1 with `operatorKeys.create()`** and let Task 5's grep step fix it.

- [ ] **Step 2: Remote RED** — push; FAIL (requests never 429; properties unbound are fine — Spring ignores unknown properties, so the third `GET` returns 200 and the assertion fails).

- [ ] **Step 3: Implement**

`src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitProperties.java`:

```java
package com.leandrossb.nummus.interfaces.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-tenant throttling: merchant requests bucket by merchant id, operator
 * requests by operator key id. Defaults: a merchant bursts 600 requests then
 * sustains 10/s; an operator key bursts 120 then sustains 2/s. Single-process
 * state — a restart resets buckets (documented bound).
 */
@ConfigurationProperties(prefix = "nummus.ratelimit")
public record RateLimitProperties(
    @DefaultValue("600") int merchantCapacity,
    @DefaultValue("10") int merchantRefillPerSecond,
    @DefaultValue("120") int operatorCapacity,
    @DefaultValue("2") int operatorRefillPerSecond) {
}
```

`src/main/java/com/leandrossb/nummus/interfaces/ratelimit/RateLimitFilter.java`:

```java
package com.leandrossb.nummus.interfaces.ratelimit;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.auth.MerchantAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-tenant token-bucket throttling on authenticated routes. Runs after
 * MerchantAuthFilter (the tenant is known) and before IdempotencyWebFilter
 * (a rejected request buffers no body). Merchant requests bucket by merchant
 * id — key rotation must not buy fresh quota; operator requests bucket by
 * operator key id. Unauthenticated requests pass through (the 401 path and
 * the open simulator stay unthrottled — documented bound). Renders
 * problem+json itself because a filter runs outside the @ControllerAdvice's
 * reach.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 500)
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    Object merchant = request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE);
    Object operator = request.getAttribute(MerchantAuthFilter.OPERATOR_ATTRIBUTE);
    if (merchant instanceof AuthenticatedMerchant tenant) {
      if (!consume("merchant:" + tenant.merchantPublicId(), properties.merchantCapacity(),
          properties.merchantRefillPerSecond(), response)) {
        return;
      }
    } else if (operator instanceof AuthenticatedOperator principal) {
      if (!consume("operator:" + principal.keyPublicId(), properties.operatorCapacity(),
          properties.operatorRefillPerSecond(), response)) {
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean consume(String bucketKey, int capacity, int refillPerSecond,
      HttpServletResponse response) throws IOException {
    TokenBucket bucket = buckets.computeIfAbsent(bucketKey,
        k -> new TokenBucket(capacity, refillPerSecond, System.nanoTime()));
    if (bucket.tryConsume(System.nanoTime())) {
      return true;
    }
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(bucket.retryAfterSeconds(System.nanoTime())));
    response.setContentType("application/problem+json");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
        "Rate limit exceeded; retry after the Retry-After delay");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
    return false;
  }
}
```

(`@ConfigurationPropertiesScan` on `Application` already registers the record — no other wiring.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=RateLimitFilterTest` → 5/5; full `verify` → BUILD SUCCESS, **275 tests** (270 + 5). Watch the full run: if any pre-existing suite now 429s, that is a defect in this task (defaults were sized to prevent it) — fix before committing.

- [ ] **Step 5: Commit** — `feat: rate limit authenticated routes per tenant` + trailer; push.

---

### Task 4: Expiry at authentication + `last_used_at` (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/ResolvedMerchantKey.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/domain/ApiKey.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/auth/AuthenticatedMerchant.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthentication.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/ApiKeyResponse.java`
- Modify: `src/test/java/com/leandrossb/nummus/merchants/MerchantStoreTest.java`
- Modify: `src/test/java/com/leandrossb/nummus/merchants/application/FakeMerchantsService.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/ApiKeyExpiryAuthTest.java`

**Interfaces:**
- Produces: `ApiKey(UUID publicId, String prefix, String status, Instant createdAt, Instant expiresAt, Instant lastUsedAt)` (two nullable new components — every constructor site updated in this task); `ResolvedMerchantKey(Merchant merchant, UUID keyPublicId)`; `MerchantsService.findByApiKey(String)` now returns `Optional<ResolvedMerchantKey>`; `AuthenticatedMerchant(UUID merchantPublicId, String name, UUID keyPublicId)`; `MerchantStore.stampApiKeyLastUsed(String keyHash)` and `stampOperatorKeyLastUsed(String keyHash)`; listings expose `expiresAt`/`lastUsedAt`. Task 6 relies on `keyPublicId` and the expiry predicate.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ApiKeyExpiryAuthTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth() {
    return "Bearer " + operatorKeys.create().secret();
  }

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String firstKeyId(String bearer) throws Exception {
    MvcResult listed = mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(listed.getResponse().getContentAsString(), "$[0].keyId");
  }

  private void expireKey(String keyId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
  }

  @Test
  void expiredMerchantKeyIsUnauthorized() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Expired Merchant");
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    expireKey(firstKeyId(bearer));
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void nearFutureExpiryAuthenticatesUntilItPasses() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Countdown Merchant");
    String keyId = firstKeyId(bearer);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() + interval '2 seconds' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredOperatorKeyIsUnauthorized() throws Exception {
    String operator = operatorKeys.create().secret();
    String operatorKey = "Bearer " + operator;
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", operatorKey))
        .andExpect(status().isOk());
    MvcResult listed = mockMvc.perform(get("/v1/operator/api-keys")
            .header("Authorization", operatorKey))
        .andExpect(status().isOk()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].keyId");
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.operator_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", operatorKey))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void lastUsedAtIsStampedOnSuccessAndNotOn401() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Stamped Merchant");
    String keyId = firstKeyId(bearer);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer)).andExpect(status().isOk());
    String first;
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_used_at from merchants.api_key "
            + "where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      first = rs.getString(1);
      Assertions.assertNotNull(first);
    }
    expireKey(keyId);
    mockMvc.perform(get("/v1/me").header("Authorization", bearer))
        .andExpect(status().isUnauthorized());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select last_used_at from merchants.api_key "
            + "where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(first, rs.getString(1));
    }
  }

  @Test
  void listingsExposeTheLifecycleColumns() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Listed Merchant");
    String keyId = firstKeyId(bearer);
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() + interval '1 day' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
        .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (`expiredMerchantKeyIsUnauthorized` and `expiredOperatorKeyIsUnauthorized` return 200; `listingsExposeTheLifecycleColumns` fails on the absent JSON fields).

- [ ] **Step 3: Implement**

`ResolvedMerchantKey.java`:

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.UUID;

/** The merchant behind a resolved key, plus the key's public id — rotation
 *  targets the calling key. */
public record ResolvedMerchantKey(Merchant merchant, UUID keyPublicId) {
}
```

`ApiKey.java` (domain — two nullable components):

```java
package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** An API key's metadata. The secret exists only at issuance; the prefix is
 *  display-safe. expiresAt is null when the key never expires; lastUsedAt is
 *  null until the first successful authentication. */
public record ApiKey(UUID publicId, String prefix, String status, Instant createdAt,
    Instant expiresAt, Instant lastUsedAt) {
}
```

`MerchantStore.java` — change the return type of `findMerchantByKeyHash` and add the two stamps:

```java
  /** The merchant and key behind the ACTIVE, unexpired key with this hash. */
  Optional<ResolvedMerchantKey> findMerchantByKeyHash(String keyHash);

  /** Best-effort observability stamp; never gates authentication. */
  void stampApiKeyLastUsed(String keyHash);

  /** Best-effort observability stamp; never gates authentication. */
  void stampOperatorKeyLastUsed(String keyHash);
```

`JdbcClientMerchantStore.java` — update `mapKey`, the three SELECTs that use it, `findMerchantByKeyHash`, and add the stamps. Full new/changed methods:

```java
  private static ApiKey mapKey(ResultSet rs) throws SQLException {
    return new ApiKey(rs.getObject("public_id", UUID.class), rs.getString("prefix"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        instantOrNull(rs, "expires_at"), instantOrNull(rs, "last_used_at"));
  }

  private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
```

`findActiveKeyByHash`, `listKeys`, `findActiveOperatorKeyByHash`, `listOperatorKeys` — add `expires_at, last_used_at` to their SELECT lists (no predicate changes; listings show expired keys on purpose).

```java
  @Override
  public Optional<ResolvedMerchantKey> findMerchantByKeyHash(String keyHash) {
    return jdbc.sql("""
        select m.public_id, m.name, m.created_at, k.public_id as key_public_id
        from merchants.merchant m join merchants.api_key k on k.merchant_id = m.id
        where k.key_hash = :keyHash and k.status = 'ACTIVE'
          and (k.expires_at is null or k.expires_at > now())
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> new ResolvedMerchantKey(mapMerchant(rs),
            rs.getObject("key_public_id", UUID.class))).optional();
  }

  @Override
  public void stampApiKeyLastUsed(String keyHash) {
    try {
      jdbc.sql("update merchants.api_key set last_used_at = now() where key_hash = :keyHash")
          .param("keyHash", keyHash).update();
    } catch (DataAccessException e) {
      LOGGER.warn("failed to stamp api key last_used_at", e);
    }
  }

  @Override
  public void stampOperatorKeyLastUsed(String keyHash) {
    try {
      jdbc.sql("update merchants.operator_key set last_used_at = now() where key_hash = :keyHash")
          .param("keyHash", keyHash).update();
    } catch (DataAccessException e) {
      LOGGER.warn("failed to stamp operator key last_used_at", e);
    }
  }
```

Add to the class: `private static final Logger LOGGER = LoggerFactory.getLogger(JdbcClientMerchantStore.class);` (imports `org.slf4j.Logger`, `org.slf4j.LoggerFactory`, `org.springframework.dao.DataAccessException`, `java.time.Instant`). The expiry predicate is added only to the two auth lookups (`findMerchantByKeyHash` above and `findActiveOperatorKeyByHash`):

```java
  @Override
  public Optional<ApiKey> findActiveOperatorKeyByHash(String keyHash) {
    return jdbc.sql("""
        select public_id, prefix, status, created_at, expires_at, last_used_at
        from merchants.operator_key
        where key_hash = :keyHash and status = 'ACTIVE'
          and (expires_at is null or expires_at > now())
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapKey(rs)).optional();
  }
```

`MerchantsService.java`: `Optional<ResolvedMerchantKey> findByApiKey(String rawKey);` (javadoc: "…empty for unknown, revoked, or expired keys").

`MerchantsServiceImpl.java`:

```java
  @Override
  public Optional<ResolvedMerchantKey> findByApiKey(String rawKey) {
    if (rawKey == null || !rawKey.startsWith("nummus_sk_")) {
      return Optional.empty();
    }
    String keyHash = sha256Hex(rawKey);
    Optional<ResolvedMerchantKey> resolved = store.findMerchantByKeyHash(keyHash);
    // Observability only: stamping is best-effort and never gates authentication.
    resolved.ifPresent(r -> store.stampApiKeyLastUsed(keyHash));
    return resolved;
  }
```

`AuthenticatedMerchant.java`:

```java
/** The authenticated caller, resolved from a Bearer API key. Shared vocabulary:
 *  controllers declare this parameter to mark a merchant route. keyPublicId
 *  identifies the calling key — rotation targets it. */
public record AuthenticatedMerchant(UUID merchantPublicId, String name, UUID keyPublicId) {
}
```

`MerchantAuthentication.java`:

```java
  @Override
  public Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential) {
    return merchants.findByApiKey(rawBearerCredential)
        .map(resolved -> new AuthenticatedMerchant(resolved.merchant().publicId(),
            resolved.merchant().name(), resolved.keyPublicId()));
  }
```

`ApiKeyResponse.java` — add the two nullable fields (`Instant expiresAt, Instant lastUsedAt`) to the record and to its `from(ApiKey)` factory, mapping `key.expiresAt()` / `key.lastUsedAt()`.

Ripples (compile-driven; fix all):
- `src/test/java/com/leandrossb/nummus/merchants/MerchantStoreTest.java` — `findByApiKey` now yields `ResolvedMerchantKey`; keep the existing assertions by mapping (`.map(ResolvedMerchantKey::merchant)` where a `Merchant` is expected) and additionally assert `keyPublicId` is present on the resolved hit.
- `src/test/java/com/leandrossb/nummus/merchants/application/FakeMerchantsService.java` — `findByApiKey` returns `Optional<ResolvedMerchantKey>`; body stays `Optional.empty()`.
- Any `new ApiKey(...)` constructor sites in tests (grep `new ApiKey(`) gain two `null` arguments.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='ApiKeyExpiryAuthTest,MerchantStoreTest'` → all green; full `verify` → BUILD SUCCESS, **280 tests** (275 + 5).

- [ ] **Step 5: Commit** — `feat: expire keys at authentication and stamp last use` + trailer; push.

---

### Task 5: Mint-time `expiresIn` (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/InvalidKeyExpiryException.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateKeyRequest.java`
- Modify: `MerchantStore.java`, `JdbcClientMerchantStore.java` (`insertApiKey`/`insertOperatorKey` gain a `Duration expiresIn`)
- Modify: `ApiKeysService.java` / `ApiKeysServiceImpl.java` (`create(UUID, Duration)`)
- Modify: `OperatorKeysService.java` / `OperatorKeysServiceImpl.java` (`create(Duration)`)
- Modify: `MerchantsServiceImpl.java` (merchant creation mints the first key with no expiry)
- Modify: `MeController.java`, `OperatorKeysController.java` (optional request body)
- Modify: `CreateKeyResponse.java` (additive `expiresAt`)
- Modify: `GlobalExceptionHandler.java` (map the new exception)
- Test: `src/test/java/com/leandrossb/nummus/merchants/KeyExpiryRestApiTest.java`

**Interfaces:**
- Consumes: `ApiKey.expiresAt` (Task 4).
- Produces: `create(UUID merchantPublicId, Duration expiresIn)` and `create(Duration expiresIn)` — `null` expiresIn = never; `InvalidKeyExpiryException` → 400; mint responses carry `expiresAt`; JSON body `{"expiresIn":"P90D"}` accepted on both mint routes. Task 6's rotate reuses the mint plumbing and the exception.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class KeyExpiryRestApiTest extends IntegrationTestBase {

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

  @Test
  void merchantMintWithExpiresInStoresAndReturnsIt() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Expiring Mint");
    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
        .andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select expires_at > now() + interval '23 hours' "
            + "and expires_at < now() + interval '25 hours' as about_one_day "
            + "from merchants.api_key where public_id = '" + keyId + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertTrue(rs.getBoolean("about_one_day"));
    }
  }

  @Test
  void operatorMintWithExpiresInStoresIt() throws Exception {
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", operatorAuth())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT2H\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  void nonPositiveExpiresInIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Bad Expiry");
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0S\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void garbageExpiresInIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Garbage Expiry");
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"whenever\"}"))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Remote RED** — push; compilation FAIL (`create(Duration)` absent) plus the REST assertions would fail — the compile failure is the RED.

- [ ] **Step 3: Implement**

`InvalidKeyExpiryException.java`:

```java
package com.leandrossb.nummus.merchants.application;

/** A mint/rotation request carried an expiresIn that is not a positive
 *  ISO-8601 duration. */
public class InvalidKeyExpiryException extends RuntimeException {

  public InvalidKeyExpiryException() {
    super("expiresIn must be a positive ISO-8601 duration (e.g. P90D) or omitted");
  }
}
```

`CreateKeyRequest.java`:

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import java.time.Duration;

/** Optional mint/rotation body: expiresIn is an ISO-8601 duration; omitted
 *  or null means the key never expires. */
public record CreateKeyRequest(Duration expiresIn) {
}
```

`MerchantStore.java`:

```java
  /** Stores hash + prefix; the secret never reaches the store. Null expiresIn
   *  means no expiry; the database clock owns expires_at. */
  void insertApiKey(UUID merchantPublicId, String keyHash, String prefix, Duration expiresIn);

  /** Stores an operator key hash + prefix; the secret never reaches the store. */
  void insertOperatorKey(String keyHash, String prefix, Duration expiresIn);
```

`JdbcClientMerchantStore.java` — the inserts (note the named-parameter parser tolerates the PostgreSQL `::` cast; there is none here — `make_interval` takes a plain bind):

```java
  @Override
  public void insertApiKey(UUID merchantPublicId, String keyHash, String prefix, Duration expiresIn) {
    jdbc.sql("""
        insert into merchants.api_key (public_id, merchant_id, key_hash, prefix, expires_at)
        select :keyId, m.id, :keyHash, :prefix,
          case when :hasExpiry then now() + make_interval(secs => :expiresInSeconds) else null end
        from merchants.merchant m where m.public_id = :merchantPublicId
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("merchantPublicId", merchantPublicId)
        .param("hasExpiry", expiresIn != null)
        .param("expiresInSeconds", expiresIn == null ? 0.0 : expiresIn.toMillis() / 1000.0)
        .update();
  }

  @Override
  public void insertOperatorKey(String keyHash, String prefix, Duration expiresIn) {
    jdbc.sql("""
        insert into merchants.operator_key (public_id, key_hash, prefix, expires_at)
        values (:keyId, :keyHash, :prefix,
          case when :hasExpiry then now() + make_interval(secs => :expiresInSeconds) else null end)
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("hasExpiry", expiresIn != null)
        .param("expiresInSeconds", expiresIn == null ? 0.0 : expiresIn.toMillis() / 1000.0)
        .update();
  }
```

`ApiKeysService.java`: replace `IssuedApiKey create(UUID merchantPublicId);` with:

```java
  /** @param expiresIn optional lifetime; null = never expires.
   *  @throws InvalidKeyExpiryException when expiresIn is non-positive. */
  IssuedApiKey create(UUID merchantPublicId, Duration expiresIn);
```

`ApiKeysServiceImpl.java` — validate then pass through:

```java
  @Override
  @Transactional
  public IssuedApiKey create(UUID merchantPublicId, Duration expiresIn) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    requirePositiveExpiry(expiresIn);
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    String keyHash = MerchantsServiceImpl.sha256Hex(rawKey);
    store.insertApiKey(merchantPublicId, keyHash, rawKey.substring(0, 12), expiresIn);
    // The store owns key identity; read the persisted row back so the returned
    // metadata (public_id, created_at, expires_at) is what revoke/list will match on.
    ApiKey stored = store.findActiveKeyByHash(keyHash)
        .orElseThrow(() -> new IllegalStateException("api key row missing after insert"));
    return new IssuedApiKey(stored, rawKey);
  }

  static void requirePositiveExpiry(Duration expiresIn) {
    if (expiresIn != null && !expiresIn.isPositive()) {
      throw new InvalidKeyExpiryException();
    }
  }
```

`OperatorKeysService.java`: `IssuedApiKey create(Duration expiresIn);` — `OperatorKeysServiceImpl.create(Duration expiresIn)` becomes `mint(expiresIn)`; `mint(Duration expiresIn)` validates via `ApiKeysServiceImpl.requirePositiveExpiry(expiresIn)` (same package) and passes `expiresIn` to `store.insertOperatorKey(keyHash, prefix, expiresIn)`; its readback stays `findActiveOperatorKeyByHash`.

`MerchantsServiceImpl.create(...)` — its `store.insertApiKey(...)` call site passes `null` (the onboarding key never expires).

Controllers — optional bodies:

```java
  @Idempotent
  @PostMapping("/v1/me/api-keys")
  ResponseEntity<CreateKeyResponse> createKey(AuthenticatedMerchant merchant,
      @RequestBody(required = false) CreateKeyRequest request) {
    var issued = keys.create(merchant.merchantPublicId(),
        request == null ? null : request.expiresIn());
    return ResponseEntity
        .created(URI.create("/v1/me/api-keys/" + issued.key().publicId()))
        .body(CreateKeyResponse.from(issued));
  }
```

`OperatorKeysController.createKey` mirrors it against `operatorKeys.create(request == null ? null : request.expiresIn())`.

`CreateKeyResponse.java` — additive field:

```java
public record CreateKeyResponse(UUID keyId, String prefix, String status,
    Instant createdAt, Instant expiresAt, String secret) {

  public static CreateKeyResponse from(IssuedApiKey issued) {
    return new CreateKeyResponse(issued.key().publicId(), issued.key().prefix(),
        issued.key().status(), issued.key().createdAt(), issued.key().expiresAt(),
        issued.secret());
  }
}
```

`GlobalExceptionHandler.java` — next to `invalidFeeSchedule`:

```java
  @ExceptionHandler(InvalidKeyExpiryException.class)
  ProblemDetail invalidKeyExpiry(InvalidKeyExpiryException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
```

Ripples: `grep -rn "operatorKeys.create(\|keys.create(\|\.create()" src/test` — every direct service call `operatorKeys.create()` in tests becomes `operatorKeys.create(null)` (RateLimitFilterTest, ApiKeyExpiryAuthTest, MerchantScopingTest, MerchantAuthFilterTest, OperatorAuthRestApiTest, OperatorGatingTest, and any webhook/conciliation suite that mints operator keys). `MerchantsController`'s creation path needs no change (it calls `MerchantsService.create`).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='KeyExpiryRestApiTest,ApiKeyExpiryAuthTest,MerchantStoreTest'` → all green; full `verify` → BUILD SUCCESS, **284 tests** (280 + 4).

- [ ] **Step 5: Commit** — `feat: let mints set a key lifetime` + trailer; push.

---

### Task 6: Rotation with grace (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/ApiKeyProperties.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/RotatedApiKey.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyResponse.java`
- Modify: `MerchantStore.java`, `JdbcClientMerchantStore.java` (retire methods)
- Modify: `ApiKeysService.java` / `ApiKeysServiceImpl.java`, `OperatorKeysService.java` / `OperatorKeysServiceImpl.java` (`rotate`)
- Modify: `MeController.java`, `OperatorKeysController.java` (rotate routes)
- Test: `src/test/java/com/leandrossb/nummus/merchants/KeyRotationRestApiTest.java`

**Interfaces:**
- Consumes: `AuthenticatedMerchant.keyPublicId` (Task 4), mint plumbing + `requirePositiveExpiry` (Task 5).
- Produces: `POST /v1/me/api-keys/current/rotate` and `POST /v1/operator/api-keys/current/rotate` — both `@Idempotent`, 201 with `RotateKeyResponse`; property `nummus.api-keys.rotation-grace` (default `PT5M`); store methods `Optional<Instant> retireApiKey(UUID merchantPublicId, UUID keyPublicId, Duration grace)` and `Optional<Instant> retireOperatorKey(UUID keyPublicId, Duration grace)`; `RotatedApiKey(IssuedApiKey issued, Instant oldKeyExpiresAt)`.

- [ ] **Step 1: Write the failing test** (grace tightened to 2s so the tests observe the window passing)

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class KeyRotationRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void shortGrace(DynamicPropertyRegistry registry) {
    registry.add("nummus.api-keys.rotation-grace", () -> "PT2S");
  }

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

  @Test
  void merchantRotationReturnsNewSecretAndRetiresTheOldKeyAtGrace() throws Exception {
    String oldSecret = createMerchantAndGetKey("Rotating Merchant");
    String oldBearer = "Bearer " + oldSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andExpect(jsonPath("$.oldKeyExpiresAt").isNotEmpty())
        .andReturn();
    String newSecret = com.jayway.jsonpath.JsonPath.read(
        rotated.getResponse().getContentAsString(), "$.secret");
    Assertions.assertNotEquals(oldSecret, newSecret);
    // During grace the old key still works; the new key works immediately.
    mockMvc.perform(get("/v1/me").header("Authorization", oldBearer))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/me").header("Authorization", oldBearer))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
  }

  @Test
  void rotationIsIdempotentAndReplaysTheSameSecret() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Idempotent Rotation");
    String idemKey = UUID.randomUUID().toString();
    MvcResult first = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer).header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    MvcResult replay = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer).header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    Assertions.assertEquals(first.getResponse().getContentAsString(),
        replay.getResponse().getContentAsString());
    // One rotation, not two: the listing holds exactly two keys (onboarding + replacement).
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void rotationNeverExtendsANearerExpiry() throws Exception {
    // The calling key expires in 2s; rotating with a 2s grace must not push
    // the end past the already-set expiry (least()).
    String oldSecret = createMerchantAndGetKey("Nearer Expiry");
    String oldBearer = "Bearer " + oldSecret;
    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT2S\"}"))
        .andExpect(status().isCreated()).andReturn();
    String expiringSecret = com.jayway.jsonpath.JsonPath.read(
        minted.getResponse().getContentAsString(), "$.secret");
    String expiringBearer = "Bearer " + expiringSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", expiringBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    String oldKeyExpiresAt = rotated.getResponse().getContentAsString();
    Assertions.assertTrue(oldKeyExpiresAt.contains("oldKeyExpiresAt"));
    Thread.sleep(2500);
    // The rotated-away key died at its own earlier expiry, not at grace end.
    mockMvc.perform(get("/v1/me").header("Authorization", expiringBearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredKeyCannotRotate() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Expired Rotator");
    MvcResult listed = mockMvc.perform(get("/v1/me/api-keys").header("Authorization", bearer))
        .andExpect(status().isOk()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(
        listed.getResponse().getContentAsString(), "$[0].keyId");
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("update merchants.api_key set expires_at = now() - interval '1 hour' "
          + "where public_id = '" + keyId + "'");
    }
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void operatorRotationWorksTheSameWay() throws Exception {
    String oldSecret = operatorKeys.create(null).secret();
    String oldBearer = "Bearer " + oldSecret;
    MvcResult rotated = mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", oldBearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andExpect(jsonPath("$.oldKeyExpiresAt").isNotEmpty())
        .andReturn();
    String newSecret = com.jayway.jsonpath.JsonPath.read(
        rotated.getResponse().getContentAsString(), "$.secret");
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", oldBearer))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", "Bearer " + newSecret))
        .andExpect(status().isOk());
    Thread.sleep(2500);
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", oldBearer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rotateAcceptsExpiresInForTheNewKey() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Rotate With Lifetime");
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  void nonPositiveExpiresInOnRotateIsRejected() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey("Rotate Bad Expiry");
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expiresIn\":\"PT0S\"}"))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (404 on both routes; unknown property `rotation-grace` is silently ignored).

- [ ] **Step 3: Implement**

`ApiKeyProperties.java`:

```java
package com.leandrossb.nummus.merchants.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Key rotation policy: the calling key keeps working for the grace window
 *  after a rotation, then hard-fails 401. Rotation never extends an existing
 *  nearer expiry. */
@ConfigurationProperties(prefix = "nummus.api-keys")
public record ApiKeyProperties(@DefaultValue("PT5M") Duration rotationGrace) {
}
```

`RotatedApiKey.java`:

```java
package com.leandrossb.nummus.merchants.application;

import java.time.Instant;

/** Result of a rotation: the freshly issued key and the moment the calling
 *  key stops working. */
public record RotatedApiKey(IssuedApiKey issued, Instant oldKeyExpiresAt) {
}
```

`MerchantStore.java`:

```java
  /** Retires the merchant's ACTIVE key at least(existing, now + grace);
   *  empty when the key is absent or not the merchant's.
   *  @return the key's new expires_at. */
  Optional<Instant> retireApiKey(UUID merchantPublicId, UUID keyPublicId, Duration grace);

  /** Retires the ACTIVE operator key at least(existing, now + grace); empty
   *  when absent or already revoked.
   *  @return the key's new expires_at. */
  Optional<Instant> retireOperatorKey(UUID keyPublicId, Duration grace);
```

`JdbcClientMerchantStore.java` (named-parameter parsing skips PostgreSQL `::` casts, so `'infinity'::timestamptz` is safe):

```java
  @Override
  public Optional<Instant> retireApiKey(UUID merchantPublicId, UUID keyPublicId, Duration grace) {
    return jdbc.sql("""
        update merchants.api_key k set expires_at =
            least(coalesce(k.expires_at, 'infinity'::timestamptz),
                  now() + make_interval(secs => :graceSeconds))
        from merchants.merchant m
        where k.merchant_id = m.id and m.public_id = :merchantPublicId
          and k.public_id = :keyPublicId and k.status = 'ACTIVE'
        returning k.expires_at
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("keyPublicId", keyPublicId)
        .param("graceSeconds", grace.toMillis() / 1000.0)
        .query((rs, i) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
        .optional();
  }

  @Override
  public Optional<Instant> retireOperatorKey(UUID keyPublicId, Duration grace) {
    return jdbc.sql("""
        update merchants.operator_key set expires_at =
            least(coalesce(expires_at, 'infinity'::timestamptz),
                  now() + make_interval(secs => :graceSeconds))
        where public_id = :keyPublicId and status = 'ACTIVE'
        returning expires_at
        """)
        .param("keyPublicId", keyPublicId)
        .param("graceSeconds", grace.toMillis() / 1000.0)
        .query((rs, i) -> rs.getObject("expires_at", OffsetDateTime.class).toInstant())
        .optional();
  }
```

`ApiKeysService.java`:

```java
  /** Mints a replacement for the calling key; the old key lives until its
   *  grace end. @throws UnknownApiKeyException when the calling key vanished
   *  (a concurrent revoke raced the rotation). */
  RotatedApiKey rotate(UUID merchantPublicId, UUID keyPublicId, Duration expiresIn);
```

`ApiKeysServiceImpl.java` — extract the mint body of `create` into `private IssuedApiKey mint(UUID merchantPublicId, Duration expiresIn)` and add:

```java
  private final ApiKeyProperties apiKeyProperties;

  public ApiKeysServiceImpl(MerchantStore store, ApiKeyProperties apiKeyProperties) {
    this.store = store;
    this.apiKeyProperties = apiKeyProperties;
  }

  @Override
  @Transactional
  public RotatedApiKey rotate(UUID merchantPublicId, UUID keyPublicId, Duration expiresIn) {
    ApiKeysServiceImpl.requirePositiveExpiry(expiresIn);
    IssuedApiKey issued = mint(merchantPublicId, expiresIn);
    Instant oldKeyExpiresAt = store.retireApiKey(merchantPublicId, keyPublicId,
            apiKeyProperties.rotationGrace())
        .orElseThrow(() -> new UnknownApiKeyException(keyPublicId));
    return new RotatedApiKey(issued, oldKeyExpiresAt);
  }
```

(Adjust `create` to call `mint(...)`; keep the `requirePositiveExpiry` call in `create`.)

`OperatorKeysService.java`:

```java
  /** Mints a replacement for the calling operator key; the old key lives
   *  until its grace end. */
  RotatedApiKey rotate(UUID keyPublicId, Duration expiresIn);
```

`OperatorKeysServiceImpl.java` — constructor gains `ApiKeyProperties`; `rotate` mirrors the merchant one via `mint(expiresIn)` + `store.retireOperatorKey(keyPublicId, apiKeyProperties.rotationGrace())`.

DTOs:

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import java.time.Duration;

/** Optional rotation body: expiresIn applies to the NEW key. */
public record RotateKeyRequest(Duration expiresIn) {
}
```

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.RotatedApiKey;
import java.time.Instant;
import java.util.UUID;

/** Rotation responses carry the new secret exactly once, plus the moment the
 *  calling key stops working. */
public record RotateKeyResponse(UUID keyId, String prefix, String status, Instant createdAt,
    Instant expiresAt, String secret, Instant oldKeyExpiresAt) {

  public static RotateKeyResponse from(RotatedApiKey rotated) {
    return new RotateKeyResponse(rotated.issued().key().publicId(),
        rotated.issued().key().prefix(), rotated.issued().key().status(),
        rotated.issued().key().createdAt(), rotated.issued().key().expiresAt(),
        rotated.issued().secret(), rotated.oldKeyExpiresAt());
  }
}
```

`MeController.java`:

```java
  @Idempotent
  @PostMapping("/v1/me/api-keys/current/rotate")
  ResponseEntity<RotateKeyResponse> rotateKey(AuthenticatedMerchant merchant,
      @RequestBody(required = false) RotateKeyRequest request) {
    var rotated = keys.rotate(merchant.merchantPublicId(), merchant.keyPublicId(),
        request == null ? null : request.expiresIn());
    return ResponseEntity
        .created(URI.create("/v1/me/api-keys/" + rotated.issued().key().publicId()))
        .body(RotateKeyResponse.from(rotated));
  }
```

`OperatorKeysController.java` — the same shape under `@PostMapping("/current/rotate")` calling `operatorKeys.rotate(operator.keyPublicId(), request == null ? null : request.expiresIn())`.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='KeyRotationRestApiTest,KeyExpiryRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **291 tests** (284 + 7).

- [ ] **Step 5: Commit** — `feat: rotate keys with a grace window` + trailer; push.

---

### Task 7: Request-body cap — 413 (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/HttpProperties.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyWebFilter.java`
- Test: `src/test/java/com/leandrossb/nummus/idempotency/RequestBodyCapTest.java` (plain unit)
- Test: `src/test/java/com/leandrossb/nummus/idempotency/RequestBodyCapRestTest.java` (REST)

**Interfaces:**
- Produces: property `nummus.http.max-body-bytes` (default 1048576); `IdempotencyWebFilter(ObjectMapper, HttpProperties)` constructor (Spring wires it); 413 `application/problem+json` before any buffering beyond the cap; a capped request stores no idempotency row.

- [ ] **Step 1: Write the failing tests**

`RequestBodyCapTest.java` (direct filter invocation, no Spring — `shouldNotFilter` only skips non-`/v1/` POSTs and the bootstrap path, which a `POST /v1/probe` satisfies):

```java
package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.interfaces.HttpProperties;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyCapTest {

  private static final int CAP = 16;

  private final IdempotencyWebFilter filter =
      new IdempotencyWebFilter(JsonMapper.builder().build(), new HttpProperties(CAP));

  /** A stream that fails the test if anything reads from it. */
  private static ServletInputStream unreadable() {
    return new ServletInputStream() {
      @Override public int read() {
        throw new AssertionError("the oversized body must not be read");
      }
      @Override public boolean isFinished() { return true; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) { }
    };
  }

  private static ServletInputStream streamOf(String body) {
    ByteArrayInputStream source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override public int read() { return source.read(); }
      @Override public boolean isFinished() { return source.available() == 0; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) { }
    };
  }

  private static MockHttpServletRequest post(String uri, long contentLength, ServletInputStream body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setHeader("Idempotency-Key", "cap-probe");
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    request.setContentLengthLong(contentLength);
    request.setInputStream(body);
    return request;
  }

  private static MockHttpServletResponse run(IdempotencyWebFilter filter,
      MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
      res.setContentType("text/plain"); // reached only when the request passes
    });
    return response;
  }

  @Test
  void oversizedContentLengthIsRefusedWithoutReading() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", CAP + 1, unreadable());
    MockHttpServletResponse response = run(filter, request);
    assertEquals(413, response.getStatus());
    assertEquals("application/problem+json", response.getContentType());
  }

  @Test
  void lyingContentLengthIsStoppedByTheBoundedRead() throws Exception {
    // Claims 4 bytes, actually 40 — the bounded read is the backstop.
    MockHttpServletRequest request = post("/v1/probe", 4,
        streamOf("x".repeat(40)));
    assertEquals(413, run(filter, request).getStatus());
  }

  @Test
  void absentContentLengthIsCappedByTheBoundedRead() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", -1,
        streamOf("x".repeat(40)));
    assertEquals(413, run(filter, request).getStatus());
  }

  @Test
  void atCapBodyPassesThrough() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", CAP, streamOf("x".repeat(CAP)));
    assertEquals(200, run(filter, request).getStatus());
  }
}
```

`RequestBodyCapRestTest.java`:

```java
package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class RequestBodyCapRestTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @DynamicPropertySource
  static void tinyCap(DynamicPropertyRegistry registry) {
    registry.add("nummus.http.max-body-bytes", () -> "64");
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String createMerchantAndGetKey() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create(null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cap Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void oversizedMerchantWriteIs413AndLeavesNoIdempotencyRow() throws Exception {
    String bearer = "Bearer " + createMerchantAndGetKey();
    String idemKey = UUID.randomUUID().toString();
    String oversized = "{\"holderName\":\"" + "x".repeat(200) + "\"}";
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", bearer)
            .header(KEY, idemKey)
            .contentType(MediaType.APPLICATION_JSON).content(oversized))
        .andExpect(status().isPayloadTooLarge());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(
            "select count(*) from idempotency.idempotency_keys where key = '" + idemKey + "'")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertEquals(0, rs.getInt(1));
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; compilation FAIL (`HttpProperties` and the two-arg filter constructor absent) — that is the RED.

- [ ] **Step 3: Implement**

`src/main/java/com/leandrossb/nummus/interfaces/HttpProperties.java`:

```java
package com.leandrossb.nummus.interfaces;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** HTTP surface limits: request bodies above maxBodyBytes are refused with
 *  413 before they are buffered. Merchant writes are small JSON; 1 MiB is
 *  generous headroom. */
@ConfigurationProperties(prefix = "nummus.http")
public record HttpProperties(@DefaultValue("1048576") int maxBodyBytes) {
}
```

`IdempotencyWebFilter.java` — constructor gains `HttpProperties`; after the key-header validation the buffering becomes:

```java
    long contentLength = request.getContentLengthLong();
    if (contentLength > properties.maxBodyBytes()) {
      writeTooLarge(response);
      return;
    }
    byte[] body = request.getInputStream().readNBytes(properties.maxBodyBytes() + 1);
    if (body.length > properties.maxBodyBytes()) {
      writeTooLarge(response);
      return;
    }
    request.setAttribute(CACHED_BODY_ATTRIBUTE, body);
    chain.doFilter(new CachedBodyRequest(request, body), response);
```

```java
  private void writeTooLarge(HttpServletResponse response) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
        "Request body exceeds the maximum accepted size");
    response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
    response.setContentType("application/problem+json");
    response.getWriter().write(objectMapper.writeValueAsString(problem));
  }
```

Extend the class javadoc's first line: "Fails merchant writes closed … and caps the buffered body at nummus.http.max-body-bytes (413)."

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='RequestBodyCapTest,RequestBodyCapRestTest,IdempotencyRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **295 tests** (291 + 4).

- [ ] **Step 5: Commit** — `feat: cap request bodies with 413` + trailer; push.

---

### Task 8: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M11 — API hardening`; the Authentication capability row extends: "…; keys expire on demand, track last use, and rotate with a grace window; authenticated routes are rate-limited per tenant; request bodies are capped".
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M11 design

M11 hardened the API surface: per-tenant token-bucket rate limiting between
authentication and body buffering, request-body caps at the idempotency
filter, and key lifecycle — mint-time expiry, best-effort `last_used_at`,
and self-serve rotation that retires the calling key at
`least(existing, now + grace)`. Known bounds, deliberate:

- **Limiter state is per-process.** A restart resets buckets (full burst
  quota after boot); a second instance enforces independently — the same
  single-process stance as the delivery worker and retention prune.
- **No per-IP throttling; unauthenticated routes are unthrottled.** The
  simulator stays open by design; an unauthenticated flood is an
  edge/deployment concern.
- **No per-merchant limit overrides** — global properties only.
- **`last_used_at` is one write per authenticated request.** The throttled
  async flush stays deferred.
- **Buckets are never evicted** — memory bounded by tenant count.
- **The cap guards the buffered merchant-write path only.** Simulator
  writes stay uncapped (non-production harness).
- **No per-route limit classes** — one bucket per tenant.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 295, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M11 API hardening complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V14 columns (+ schema tests) | 1 |
| Token bucket math | 2 |
| Filter, ordering, properties, 429/Retry-After, pass-through, no idempotency row | 3 |
| Expiry at auth (401) + last_used_at best-effort + listing exposure | 4 |
| Mint-time `expiresIn` + validation + `expiresAt` in responses | 5 |
| Rotation routes + grace + least() + idempotent replay | 6 |
| 413 pre-read + bounded read + properties | 7 |
| README + backlog + final gate (295) | 8 |
