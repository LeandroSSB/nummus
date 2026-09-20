# M10 Webhook Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two-layer SSRF enforcement on webhook URLs (registration 400 plus delivery-time revalidation, redirects never followed), merchant self-serve redrive of failed deliveries, TTL pruning of succeeded deliveries, and keyset pagination that keeps the array body and moves the cursor to a response header.

**Architecture:** V13 gives `webhook_delivery` the public id every user-facing row carries (cursor and redrive address it) plus the delete grant the retention job needs. A pure `WebhookUrlPolicy` is called at registration (throws → 400) and before every delivery attempt (boolean → failed attempt). Redrive is one guarded UPDATE scoped by merchant ownership. Pruning is a scheduled batch delete of `SUCCEEDED` rows past the cutoff, in the delivery worker's single-process discipline. Pagination is a keyset walk on the PK with a `limit+1` probe and a `Next-Cursor` header.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL via Testcontainers, JUnit 5 + MockMvc, Jackson 3 (`tools.jackson`). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-20-m10-webhook-hardening-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits.
- **NO LOCAL MAVEN/JVM RUNS — ever.** Per run: `git push origin HEAD:refs/heads/worktree-m10-webhook-hardening` then substitute `<GOALS>`:
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m10-webhook-hardening origin/worktree-m10-webhook-hardening && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B <GOALS>'
  ```
  TDD: test-only RED commit → push → remote RED → implement → push → GREEN → remote `verify` → final commit → push. Quote `Tests run:`/`BUILD` lines as evidence.
- **Test classes end in `Test`.** Counts derive from each task's enumerated tests — do not add or drop methods without updating the count: baseline **231** → T1 **233** → T2 **247** → T3 **249** → T4 **253** → T5 **256** → T6 **260**. Final gate: `Tests run: 260, Failures: 0, Errors: 0, Skipped: 0`.
- **ArchUnit (12 tests) stays green unchanged.** `WebhookUrlPolicy` lives in `webhooks.application`, uses only `java.net` — no new cross-module dependency.
- **Existing webhook suites keep registering `http://localhost` receivers** — they are the standing regression proof of the loopback exemption. If your change breaks them, the exemption is wrong, not the suites.
- **Redirects:** the delivery client performs no redirect following; 3xx fails the attempt through the existing non-2xx path. Nothing to configure — pin it with a test.
- **Delivery outcomes stay guarded on `status='PENDING'` with DB-clock `last_attempt_at`** (existing store contract); redrive's guarded transition must not weaken that.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V13__webhook_delivery_public_id_and_delete_grant.sql (Task 1)
src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryIdSchemaTest.java        (Task 1)
src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryDeleteRolesTest.java     (Task 1)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookUrlPolicy.java       (Task 2)
src/main/java/com/leandrossb/nummus/webhooks/application/UnsafeWebhookUrlException.java (Task 2)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java (register validates) (Task 2)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (400 entry) (Task 2)
src/test/java/com/leandrossb/nummus/webhooks/WebhookUrlPolicyTest.java                (Task 2)
src/test/java/com/leandrossb/nummus/webhooks/WebhookRegistrationPolicyTest.java      (Task 2)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookDeliveryWorker.java   (pre-POST policy check) (Task 3)
src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryPolicyTest.java           (Task 3)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java (+requeueFailedDelivery, +pruneSucceededBefore, listDeliveries gains after) (Tasks 4-6)
src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java (Tasks 4-6)
src/main/java/com/leandrossb/nummus/webhooks/domain/UnknownWebhookDeliveryException.java (Task 4)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/WebhookDeliveriesController.java (redrive + pagination) (Tasks 4, 6)
src/main/java/com/leandrossb/nummus/webhooks/application/DeliveryRecord.java (+deliveryPublicId) (Task 4)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/DeliveryResponse.java (+deliveryId) (Task 4)
src/test/java/com/leandrossb/nummus/webhooks/WebhookRedriveTest.java                  (Task 4)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookProperties.java (+retentionDays) (Task 5)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookRetentionWorker.java  (Task 5)
src/test/java/com/leandrossb/nummus/webhooks/WebhookRetentionTest.java                (Task 5)
src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveriesPaginationTest.java     (Task 6)
README.md, docs/m2-backlog.md                                                       (Task 7)
```

---

### Task 1: `V13` — delivery public id + delete grant

**Files:**
- Create: `src/main/resources/db/migration/V13__webhook_delivery_public_id_and_delete_grant.sql`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryIdSchemaTest.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryDeleteRolesTest.java`

**Interfaces:**
- Produces: `webhooks.webhook_delivery.public_id uuid not null default gen_random_uuid() unique` (existing rows backfill via the default); `nummus_app` holds `DELETE` on `webhooks.webhook_delivery`. Tasks 4–6 read/write the column and rely on the grant.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class WebhookDeliveryIdSchemaTest extends IntegrationTestBase {

  @Test
  void deliveriesCarryAGloballyUniqueBackfilledPublicId() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      // One endpoint + one event + two deliveries, inserted raw — the column
      // must backfill and stay unique without any insert-side cooperation.
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('33333333-3333-4333-8333-333333333331', 'http://127.0.0.1/hook', 's1')");
      long endpointId = queryId(st, "select id from webhooks.webhook_endpoint "
          + "where public_id = '33333333-3333-4333-8333-333333333331'");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('33333333-3333-4333-8333-333333333332', 'probe.evt', '{}', now())");
      long eventId = queryId(st, "select id from webhooks.webhook_event "
          + "where public_id = '33333333-3333-4333-8333-333333333332'");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "values (" + eventId + ", " + endpointId + ")");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "values (" + eventId + ", " + endpointId + ")");

      var rs = st.executeQuery("select public_id from webhooks.webhook_delivery "
          + "where event_id = " + eventId);
      assertTrue(rs.next());
      String first = rs.getString(1);
      assertTrue(rs.next());
      assertNotEquals(first, rs.getString(1));

      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "update webhooks.webhook_delivery set public_id = '" + first
              + "' where event_id = " + eventId
              + " and public_id <> '" + first + "'"));
      assertEquals("23505", duplicate.getSQLState());
    }
  }

  private long queryId(Statement st, String sql) throws SQLException {
    var rs = st.executeQuery(sql);
    rs.next();
    return rs.getLong(1);
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=WebhookDeliveryIdSchemaTest` → FAIL (`column "public_id" does not exist`).

- [ ] **Step 3: Write the migration**

```sql
-- M10 webhook hardening: deliveries gain the public identifier every
-- user-facing row carries (the redrive route and the pagination cursor
-- address it; existing rows backfill via the default), and the retention
-- job needs its delete grant. V8 deliberately granted no delete here —
-- endpoints soft-delete; succeeded deliveries now age out under the
-- retention policy instead.

alter table webhooks.webhook_delivery
  add column public_id uuid not null default gen_random_uuid() unique;

grant delete on webhooks.webhook_delivery to nummus_app;
```

- [ ] **Step 4: Write the roles test** (mirror `WebhooksRolesTest`'s enable-login dance — grant `APP_ROLE_PASSWORD`, connect via `appConnection()`; unique probe ids per run)

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookDeliveryDeleteRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleDeletesDeliveries() throws Exception {
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'http://127.0.0.1/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "')");
      assertEquals(1, st.executeUpdate("delete from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')"));
      assertTrue(st.executeQuery("select 1 from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')").next() == false);
    }
  }
}
```
(The insert requires whatever NOT NULL columns V8 defines beyond those named — read `V8__webhooks_schema.sql` first and satisfy `event_types`' default or any other required column exactly as the existing `WebhooksRolesTest` inserts do.)

- [ ] **Step 5: Remote GREEN + verify** — focused `-Dtest='WebhookDeliveryIdSchemaTest,WebhookDeliveryDeleteRolesTest'` → 2/2; full `verify` → BUILD SUCCESS, **233 tests**.

- [ ] **Step 6: Commit** — `feat: add the delivery public id and delete grant (V13)` + trailer; push.

---

### Task 2: `WebhookUrlPolicy` + registration enforcement (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookUrlPolicy.java`
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/UnsafeWebhookUrlException.java`
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java` (register validates)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java` (400 entry)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookUrlPolicyTest.java` (12 pure tests)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookRegistrationPolicyTest.java` (2 REST tests)

**Interfaces:**
- Produces (Tasks 3–4 consume): `final class WebhookUrlPolicy` with `static void check(URI url)` (throws `UnsafeWebhookUrlException`) and `static boolean isSafe(URI url)`; `class UnsafeWebhookUrlException extends RuntimeException` (message-only ctor) mapped to 400 problem+json.
- Rules (exact): reject userinfo; reject non-`https` scheme UNLESS `http` with every resolved address loopback; reject when any resolved address is loopback (over https), link-local (v4/v6), site-local/private, IPv6 unique-local (`fc00::/7`), any-local, or multicast; reject unresolvable hosts. Port unrestricted. Resolution via `InetAddress.getAllByName`; `UnknownHostException` → rejection with a clear message.

- [ ] **Step 1: Write the failing policy test** (plain JUnit, no Spring)

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.webhooks.application.UnsafeWebhookUrlException;
import com.leandrossb.nummus.webhooks.application.WebhookUrlPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

  private static void assertRejected(String url) {
    assertThrows(UnsafeWebhookUrlException.class, () -> WebhookUrlPolicy.check(URI.create(url)), url);
  }

  @Test
  void httpsToPublicHostPasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("https://example.com/hook")));
  }

  @Test
  void httpToLoopbackLiteralPasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://127.0.0.1:9/hook")));
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://[::1]:9/hook")));
  }

  @Test
  void httpToLocalhostNamePasses() {
    assertDoesNotThrow(() -> WebhookUrlPolicy.check(URI.create("http://localhost:9/hook")));
  }

  @Test
  void privateRangesAreRejectedOverHttp() {
    assertRejected("http://10.1.2.3/hook");
    assertRejected("http://172.16.0.9/hook");
    assertRejected("http://192.168.1.1/hook");
  }

  @Test
  void privateRangesAreRejectedOverHttps() {
    assertRejected("https://10.1.2.3/hook");
    assertRejected("https://192.168.0.1/hook");
  }

  @Test
  void linkLocalIsRejectedIncludingCloudMetadata() {
    assertRejected("https://169.254.169.254/latest/meta-data");
    assertRejected("https://[fe80::1]/hook");
  }

  @Test
  void ipv6UniqueLocalIsRejected() {
    assertRejected("https://[fd00::1]/hook");
  }

  @Test
  void anyLocalIsRejected() {
    assertRejected("https://0.0.0.0/hook");
  }

  @Test
  void multicastIsRejected() {
    assertRejected("https://224.0.0.1/hook");
    assertRejected("https://[ff02::1]/hook");
  }

  @Test
  void userinfoIsRejected() {
    assertRejected("https://user:secret@example.com/hook");
  }

  @Test
  void unresolvableHostIsRejected() {
    assertRejected("https://no-such-host.invalid/hook");
  }

  @Test
  void nonHttpSchemeIsRejected() {
    assertRejected("ftp://example.com/hook");
  }
}
```

- [ ] **Step 2: Write the failing REST test**

```java
package com.leandrossb.nummus.webhooks;

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

@AutoConfigureMockMvc
class WebhookRegistrationPolicyTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private String merchantKey() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create().secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"policy probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  @Test
  void registeringAPrivateTargetIsBadRequest() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://10.1.2.3/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void registeringALoopbackHttpTargetIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + merchantKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"http://127.0.0.1:9/hook\",\"eventTypes\":[]}"))
        .andExpect(status().isCreated());
  }
}
```
(Read `CreateEndpointRequest` and `WebhookEndpointsController` first; adjust the route/body/field names to the real surface — the asserted statuses are the contract.)

- [ ] **Step 3: Remote RED** — push; compilation FAIL (policy types absent).

- [ ] **Step 4: Implement**

```java
package com.leandrossb.nummus.webhooks.application;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * The SSRF stance, enforced at registration and again before every delivery
 * attempt: https only, except plain http whose host resolves exclusively to
 * loopback (local receivers). Redirects are never followed by the delivery
 * client, so a public URL cannot pivot through a 3xx either.
 */
public final class WebhookUrlPolicy {

  private WebhookUrlPolicy() {
  }

  public static void check(URI url) {
    if (url.getRawUserInfo() != null) {
      throw new UnsafeWebhookUrlException("url must not carry credentials: " + url.getHost());
    }
    String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase();
    boolean https = "https".equals(scheme);
    boolean http = "http".equals(scheme);
    if (!https && !http) {
      throw new UnsafeWebhookUrlException("url scheme must be http or https");
    }
    InetAddress[] addresses = resolve(url);
    if (http && allLoopback(addresses)) {
      return; // the local-receiver exemption
    }
    if (!https) {
      throw new UnsafeWebhookUrlException("http is allowed only for loopback hosts");
    }
    for (InetAddress address : addresses) {
      if (address.isLoopbackAddress() || address.isLinkLocalAddress()
          || address.isSiteLocalAddress() || address.isAnyLocalAddress()
          || address.isMulticastAddress() || isUniqueLocal(address)) {
        throw new UnsafeWebhookUrlException(
            "url resolves to a blocked address class: " + address.getHostAddress());
      }
    }
  }

  public static boolean isSafe(URI url) {
    try {
      check(url);
      return true;
    } catch (UnsafeWebhookUrlException e) {
      return false;
    }
  }

  private static InetAddress[] resolve(URI url) {
    try {
      return InetAddress.getAllByName(url.getHost());
    } catch (UnknownHostException e) {
      throw new UnsafeWebhookUrlException("url host does not resolve: " + url.getHost());
    }
  }

  private static boolean allLoopback(InetAddress[] addresses) {
    for (InetAddress address : addresses) {
      if (!address.isLoopbackAddress()) {
        return false;
      }
    }
    return true;
  }

  private static boolean isUniqueLocal(InetAddress address) {
    return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
  }
}
```

```java
package com.leandrossb.nummus.webhooks.application;

/** Raised when a webhook URL violates the SSRF policy. */
public class UnsafeWebhookUrlException extends RuntimeException {

  public UnsafeWebhookUrlException(String message) {
    super(message);
  }
}
```

`WebhookEndpointsService.register` validates first — `WebhookUrlPolicy.check(url);` as the first statement (before the existing validation). `GlobalExceptionHandler` gains a 400 problem+json entry for `UnsafeWebhookUrlException`, mirroring `InvalidFeeScheduleException`'s shape.

- [ ] **Step 5: Remote GREEN + verify** — focused `-Dtest='WebhookUrlPolicyTest,WebhookRegistrationPolicyTest'` → 14/14; full `verify` → BUILD SUCCESS, **247 tests** (the existing suites registering `http://localhost` must stay green — they prove the exemption).

- [ ] **Step 6: Commit** — `feat: enforce the webhook url policy at registration` + trailer; push.

---

### Task 3: Delivery-time revalidation + redirect pin (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookDeliveryWorker.java` (pre-POST policy check)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveryPolicyTest.java`

**Interfaces:**
- Consumes: `WebhookUrlPolicy.isSafe(URI)` (Task 2), `DeliveryResult(false, null)` (existing shape of a connect failure).
- Produces: the worker's attempt loop checks the policy before dialing — a violating URL records a retry (or terminal failure once `maxAttempts` is reached) exactly like a network error. 3xx responses already fail via the non-2xx path (pinned here, nothing to change in the client).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookDeliveryWorker;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery-time revalidation: an endpoint whose URL violates the policy is
 * inserted directly via SQL — the REST guard would have rejected it, which is
 * exactly what an attacker's DNS change bypasses. The worker must treat the
 * URL as a failed attempt, not dial it.
 */
@AutoConfigureMockMvc
class WebhookDeliveryPolicyTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private WebhookDeliveryWorker worker;
  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void policyViolatingUrlFailsTheAttemptWithoutDialing() throws Exception {
    // Merchant + endpoint inserted raw with a private URL, one due delivery.
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'https://192.168.0.1/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, next_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), now()");
    }

    worker.deliverDue();

    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select status, attempts from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      rs.next();
      assertEquals("PENDING", rs.getString(1)); // backoff applied, not terminal on attempt 1
      assertEquals(1, rs.getInt(2));
      var backoff = st.executeQuery("select next_attempt_at > now() as later from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      backoff.next();
      assertEquals(true, backoff.getBoolean(1));
    }
  }
}
```
(Raw-insert shapes must satisfy V8's NOT NULLs — mirror `WebhookDeliveryPolicyTest`'s own inserts from Task 1's roles test. If the worker's `claimDueDeliveries` filters by endpoint status or event type fan-out, satisfy those too — read `JdbcClientWebhookStore.claimDueDeliveries` first and make the fixture match. The asserted contract: no dial, one recorded attempt, backoff scheduled.)

For the redirect pin, add a second test using the existing `ReceiverServer` harness (read `WebhookDeliveryWorkerTest`'s usage first): register an endpoint pointing at a receiver that answers **302** to the POST, run `worker.deliverDue()`, and assert the delivery is `PENDING` with `attempts = 1` and `last_response_status = 302` — proving no redirect was followed (a followed redirect would surface as the redirect target's status or a success). If `ReceiverServer` cannot answer 302 today, extend it with a one-line mode — that is test-harness code, not production.

- [ ] **Step 2: Remote RED** — push; FAIL (the violating URL's attempt stays `PENDING` with `attempts = 0` — the worker dialed or skipped without recording).

- [ ] **Step 3: Implement** — in `WebhookDeliveryWorker.deliverDue`'s loop, before `client.deliver(...)`:

```java
      if (!WebhookUrlPolicy.isSafe(due.url())) {
        // Registration already rejects these; this closes DNS rebinding. The
        // attempt fails exactly like a connect error — backoff, then terminal.
        var result = new DeliveryResult(false, null);
        if (due.attempts() + 1 >= properties.maxAttempts()) {
          store.recordDeliveryFailure(due.id(), null);
        } else {
          store.recordDeliveryRetry(due.id(), null,
              Instant.now().plus(backoffAfter(due.attempts() + 1)));
        }
        continue;
      }
```

- [ ] **Step 4: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, **249 tests**.

- [ ] **Step 5: Commit** — `feat: revalidate webhook urls before every delivery` + trailer; push.

---

### Task 4: Merchant redrive + `deliveryId` exposure (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/webhooks/domain/UnknownWebhookDeliveryException.java`
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java` (`boolean requeueFailedDelivery(UUID merchantPublicId, UUID deliveryPublicId)`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (guarded UPDATE; `DeliveryRecord` mapping gains `deliveryPublicId`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/DeliveryRecord.java` (+ `UUID deliveryPublicId` first component)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/dto/DeliveryResponse.java` (+ `UUID deliveryId` first field)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/WebhookDeliveriesController.java` (redrive route)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java` (404 entry)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookRedriveTest.java`
- Sweep: any other `DeliveryRecord`/`DeliveryResponse` construction sites compile (tests may construct them).

**Interfaces:**
- Consumes: V13 `public_id` (Task 1).
- Produces: `POST /v1/webhook-deliveries/{deliveryId}/redrive` — merchant-authenticated, `@Idempotent`, → 202 Accepted, no body. Unknown / foreign / non-`FAILED` → 404 via `UnknownWebhookDeliveryException`. Guarded transition: `status FAILED → PENDING`, `attempts = 0`, `next_attempt_at = now()`. Listing responses carry `deliveryId` (additive first field).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebhookRedriveTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private String[] merchantWithTerminalDelivery() throws Exception {
    // Two merchants: owner and stranger. Owner gets a FAILED delivery.
    String[] owner = newMerchant();   // {key, merchantId}
    String stranger = newMerchant()[0];
    String endpointId = UUID.randomUUID().toString();
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, merchant_public_id, url, secret) "
          + "values ('" + endpointId + "', '" + owner[1] + "', 'http://127.0.0.1:9/hook', 's1')");
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
          + "'FAILED', 8, now(), now()");
    }
    String deliveryId;
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select public_id from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "')");
      rs.next();
      deliveryId = rs.getString(1);
    }
    return new String[] {owner[0], stranger, deliveryId, endpointId};
  }

  private String[] newMerchant() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create().secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"redrive probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    return new String[] {
        com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret"),
        com.jayway.jsonpath.JsonPath.read(body, "$.merchantId")};
  }

  @Test
  void redriveRequeuesAFailedDeliveryWithAFreshCycle() throws Exception {
    String[] f = merchantWithTerminalDelivery();
    String idem = UUID.randomUUID().toString();
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, idem))
        .andExpect(status().isAccepted());
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select status, attempts, next_attempt_at <= now() as due "
          + "from webhooks.webhook_delivery where public_id = '" + f[2] + "'");
      rs.next();
      org.junit.jupiter.api.Assertions.assertEquals("PENDING", rs.getString(1));
      org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(2));
      org.junit.jupiter.api.Assertions.assertTrue(rs.getBoolean(3));
    }
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, idem))
        .andExpect(status().isAccepted()); // idempotent replay of the stored 202
  }

  @Test
  void foreignMerchantAndNonFailedDeliveriesAreNotFound() throws Exception {
    String[] f = merchantWithTerminalDelivery();
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[1]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    // Non-FAILED: requeue the owner's delivery first, then a second redrive 404s.
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isAccepted());
    mockMvc.perform(post("/v1/webhook-deliveries/" + f[2] + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound()); // PENDING now — not FAILED
    mockMvc.perform(post("/v1/webhook-deliveries/" + UUID.randomUUID() + "/redrive")
            .header("Authorization", "Bearer " + f[0]).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound()); // unknown
  }
}
```
(Before writing: read `WebhookEndpointsController`'s create route to mirror how the endpoint insert binds `merchant_public_id`, and mirror the raw-insert column set from Task 3's fixture. The listing's `deliveryId` exposure is asserted implicitly — extend the first test with one `GET /v1/webhook-endpoints/{endpointId}/deliveries` expecting `jsonPath("$[0].deliveryId")` to exist, using the owner's key and the fixture's endpoint id.)

- [ ] **Step 2: Remote RED** — push; FAIL (404 on the route; `deliveryId` absent).

- [ ] **Step 3: Implement** per Interfaces. The store method:

```java
  @Override
  public boolean requeueFailedDelivery(UUID merchantPublicId, UUID deliveryPublicId) {
    return jdbc.sql("""
        update webhooks.webhook_delivery d
        set status = 'PENDING', attempts = 0, next_attempt_at = now()
        from webhooks.webhook_endpoint e
        where d.endpoint_id = e.id
          and d.public_id = :deliveryId
          and e.merchant_public_id = :merchantPublicId
          and d.status = 'FAILED'
        """)
        .param("deliveryId", deliveryPublicId)
        .param("merchantPublicId", merchantPublicId)
        .update() == 1;
  }
```

The controller route:

```java
  @Idempotent
  @PostMapping("/v1/webhook-deliveries/{id}/redrive")
  ResponseEntity<Void> redrive(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    if (!store.requeueFailedDelivery(merchant.merchantPublicId(), id)) {
      throw new UnknownWebhookDeliveryException(id);
    }
    return ResponseEntity.accepted().build();
  }
```
`UnknownWebhookDeliveryException` mirrors `UnknownWebhookEndpointException` (`webhooks.domain`, 404 advice entry). `DeliveryRecord` gains `UUID deliveryPublicId` as the FIRST component; the listing SQL selects `d.public_id` and maps it; `DeliveryResponse` gains `UUID deliveryId` first; every constructor site (tests included) is swept.

- [ ] **Step 4: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, **253 tests**.

- [ ] **Step 5: Commit** — `feat: let merchants redrive failed deliveries` + trailer; push.

---

### Task 5: Retention — TTL pruning of succeeded deliveries (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookProperties.java` (`@DefaultValue("30") int retentionDays`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java` (`int pruneSucceededBefore(Instant cutoff, int batch)`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (batched delete loop)
- Create: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookRetentionWorker.java`
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookRetentionTest.java`

**Interfaces:**
- Consumes: V13 DELETE grant (Task 1).
- Produces: `pruneSucceededBefore` deletes only `status='SUCCEEDED' AND last_attempt_at < cutoff`, in `batch`-sized id-subquery batches, returning the total deleted. `WebhookRetentionWorker.prune()` runs on `@Scheduled(fixedDelayString = "${nummus.webhooks.retention-delay-ms:3600000}")`, no-ops when `retentionDays <= 0`.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookProperties;
import com.leandrossb.nummus.webhooks.application.WebhookRetentionWorker;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Retention prunes only SUCCEEDED deliveries past the TTL. FAILED survives
 * regardless of age; retentionDays = 0 disables pruning entirely.
 */
@TestPropertySource(properties = "nummus.webhooks.retention-days=30")
class WebhookRetentionTest extends IntegrationTestBase {

  @Autowired
  private WebhookRetentionWorker worker; // component-scanned, retentionDays=30 via the property
  @Autowired
  private WebhookStore store;

  @Test
  void agedSucceededArePrunedAndNothingElse() throws Exception {
    String endpointId = UUID.randomUUID().toString();
    seedEndpoint(endpointId);
    seed(endpointId, "SUCCEEDED", "40 days"); // aged — must go
    seed(endpointId, "FAILED", "40 days");    // aged but FAILED — must stay
    seed(endpointId, "SUCCEEDED", "1 day");   // fresh — must stay

    worker.prune();

    assertEquals(1, count(endpointId, "SUCCEEDED")); // the fresh one only
    assertEquals(1, count(endpointId, "FAILED"));
  }

  @Test
  void zeroRetentionDisablesPruning() throws Exception {
    var disabled = new WebhookRetentionWorker(store,
        new WebhookProperties(8, Duration.ofSeconds(2), 50, 0));
    String endpointId = UUID.randomUUID().toString();
    seedEndpoint(endpointId);
    seed(endpointId, "SUCCEEDED", "40 days");

    disabled.prune();

    assertEquals(1, count(endpointId, "SUCCEEDED"));
  }

  private void seedEndpoint(String endpointId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, url, secret) "
          + "values ('" + endpointId + "', 'http://127.0.0.1:9/hook', 's1')");
    }
  }

  /** One event per delivery — the unique (event_id, endpoint_id) constraint demands it. */
  private void seed(String endpointId, String status, String age) throws Exception {
    String eventId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
          + "values ('" + eventId + "', 'probe.evt', '{}', now())");
      st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
          + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
          + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
          + "'" + status + "', 1, now(), now() - interval '" + age + "'");
    }
  }

  private int count(String endpointId, String status) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      var rs = st.executeQuery("select count(*) from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') and status = '" + status + "'");
      rs.next();
      return rs.getInt(1);
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; compilation FAIL (worker/method absent).

- [ ] **Step 3: Implement** per Interfaces:

```java
package com.leandrossb.nummus.webhooks.application;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes succeeded deliveries past the retention TTL. FAILED and PENDING
 * rows are never pruned — audit history and redrive targets survive. Batches
 * like the delivery worker; a racing second instance would only issue
 * idempotent deletes (same single-process stance).
 */
@Component
public class WebhookRetentionWorker {

  private final WebhookStore store;
  private final WebhookProperties properties;

  public WebhookRetentionWorker(WebhookStore store, WebhookProperties properties) {
    this.store = store;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${nummus.webhooks.retention-delay-ms:3600000}")
  public void prune() {
    if (properties.retentionDays() <= 0) {
      return;
    }
    store.pruneSucceededBefore(Instant.now().minus(
        java.time.Duration.ofDays(properties.retentionDays())), properties.batchSize());
  }
}
```

The store loop:

```java
  @Override
  public int pruneSucceededBefore(Instant cutoff, int batch) {
    int total = 0;
    int deleted;
    do {
      deleted = jdbc.sql("""
          delete from webhooks.webhook_delivery
          where id in (
            select id from webhooks.webhook_delivery
            where status = 'SUCCEEDED' and last_attempt_at < :cutoff
            limit :batch
          )
          """)
          .param("cutoff", toOffsetDateTime(cutoff))
          .param("batch", batch)
          .update();
      total += deleted;
    } while (deleted == batch);
    return total;
  }
```
(`toOffsetDateTime` is the file's existing helper if present — reuse whatever the file uses to bind `Instant`; if it binds `Instant` directly in other queries, do that.) `WebhookProperties` gains `@DefaultValue("30") int retentionDays` as the LAST component — sweep constructor call sites (tests) with the default.

- [ ] **Step 4: Remote GREEN + verify** — focused 3/3; full `verify` → BUILD SUCCESS, **256 tests**.

- [ ] **Step 5: Commit** — `feat: prune succeeded webhook deliveries past the retention ttl` + trailer; push.

---

### Task 6: Keyset pagination with `Next-Cursor` (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/application/WebhookStore.java` (`listDeliveries` gains `UUID after`)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/infrastructure/JdbcClientWebhookStore.java` (keyset predicate)
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/interfaces/WebhookDeliveriesController.java` (`after`/`limit` params, `limit+1` probe, `Next-Cursor` header)
- Test: `src/test/java/com/leandrossb/nummus/webhooks/WebhookDeliveriesPaginationTest.java`
- Sweep: existing `WebhookDeliveriesController`/listing tests compile against the new signature (pass `null` cursor, keep default limit).

**Interfaces:**
- Consumes: `DeliveryResponse.deliveryId` (Task 4).
- Produces: `GET /v1/webhook-endpoints/{id}/deliveries?status=&after=&limit=` — `limit` default 50, `1..100` (outside → 400 problem+json); body stays a JSON array; header `Next-Cursor: <deliveryId>` present only when the `limit+1` probe found an extra row. Unknown/foreign/pruned `after` → empty page, no cursor, no error. The keyset predicate:
  `and (:after::uuid is null or d.id < (select d2.id from webhooks.webhook_delivery d2 where d2.public_id = :after))` — a non-resolving cursor makes the subquery NULL, `d.id < NULL` filters everything: the empty-page contract falls out of the SQL.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class WebhookDeliveriesPaginationTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;
  @Autowired
  private OperatorKeysService operatorKeys;

  private record Merchant(String key, String merchantId) {}

  private Merchant newMerchant() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create().secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pagination probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    return new Merchant(JsonPath.read(body, "$.apiKey.secret"), JsonPath.read(body, "$.merchantId"));
  }

  /** One endpoint owned by the merchant with n FAILED deliveries (highest ids first on read). */
  private String seedDeliveries(Merchant merchant, int n) throws Exception {
    String endpointId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into webhooks.webhook_endpoint (public_id, merchant_public_id, url, secret) "
          + "values ('" + endpointId + "', '" + merchant.merchantId() + "', 'http://127.0.0.1:9/hook', 's1')");
      for (int i = 0; i < n; i++) {
        String eventId = UUID.randomUUID().toString();
        st.executeUpdate("insert into webhooks.webhook_event (public_id, type, payload, occurred_at) "
            + "values ('" + eventId + "', 'probe.evt', '{}', now())");
        st.executeUpdate("insert into webhooks.webhook_delivery (event_id, endpoint_id, status, attempts, next_attempt_at, last_attempt_at) "
            + "select (select id from webhooks.webhook_event where public_id = '" + eventId + "'), "
            + "(select id from webhooks.webhook_endpoint where public_id = '" + endpointId + "'), "
            + "'FAILED', 8, now(), now()");
      }
    }
    return endpointId;
  }

  private MvcResult page(Merchant merchant, String endpointId, String query) throws Exception {
    return mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries" + query)
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isOk()).andReturn();
  }

  @Test
  void cursorWalkCompletes() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 7);
    Set<String> seen = new HashSet<>();
    String query = "?limit=3";
    int pages = 0;
    while (query != null) {
      var result = page(merchant, endpointId, query);
      List<String> ids = JsonPath.read(result.getResponse().getContentAsString(), "$[*].deliveryId");
      seen.addAll(ids);
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=3&after=" + cursor;
      if (cursor == null) {
        assertEquals(1, ids.size()); // 7 = 3 + 3 + 1
      }
    }
    assertEquals(3, pages);
    assertEquals(7, seen.size()); // distinct, complete
  }

  @Test
  void afterCombinesWithStatusFilter() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 5);
    // Everything is FAILED here; flip the newest two to SUCCEEDED via ids.
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("update webhooks.webhook_delivery set status = 'SUCCEEDED' "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') "
          + "and id in (select id from webhooks.webhook_delivery "
          + "where endpoint_id = (select id from webhooks.webhook_endpoint "
          + "where public_id = '" + endpointId + "') order by id desc limit 2)");
    }
    var first = page(merchant, endpointId, "?status=FAILED&limit=2");
    List<String> ids = JsonPath.read(first.getResponse().getContentAsString(), "$[*].deliveryId");
    assertEquals(2, ids.size());
    var second = page(merchant, endpointId,
        "?status=FAILED&limit=2&after=" + first.getResponse().getHeader("Next-Cursor"));
    List<String> rest = JsonPath.read(second.getResponse().getContentAsString(), "$[*].deliveryId");
    assertEquals(1, rest.size()); // 3 FAILED total: 2 + 1
    assertFalse(rest.get(0).equals(ids.get(0)));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 2);
    var result = page(merchant, endpointId, "?after=" + UUID.randomUUID());
    assertEquals("[]", result.getResponse().getContentAsString());
    assertTrue(result.getResponse().getHeader("Next-Cursor") == null);
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    var merchant = newMerchant();
    String endpointId = seedDeliveries(merchant, 1);
    mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries?limit=0")
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").exists());
    mockMvc.perform(get("/v1/webhook-endpoints/" + endpointId + "/deliveries?limit=101")
            .header("Authorization", "Bearer " + merchant.key()))
        .andExpect(status().isBadRequest());
  }
}
```
(Raw inserts must satisfy V8's NOT NULL columns — if `event_types` or others lack defaults, mirror `WebhooksRolesTest`'s insert shapes. The `deliveryId` field on the response comes from Task 4.)

- [ ] **Step 2: Remote RED** — push; FAIL (params ignored / no header).

- [ ] **Step 3: Implement** per Interfaces. Controller shape:

```java
  @GetMapping("/v1/webhook-endpoints/{id}/deliveries")
  ResponseEntity<List<DeliveryResponse>> deliveries(AuthenticatedMerchant merchant,
      @PathVariable UUID id, @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    endpoints.get(merchant.merchantPublicId(), id);
    var page = store.listDeliveries(merchant.merchantPublicId(), id, status, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(DeliveryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).deliveryPublicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(DeliveryResponse::from).toList());
  }
```
(`IllegalArgumentException` already maps to 400 problem+json by the existing advice — verify against `GlobalExceptionHandler` while implementing; if not mapped, add the entry mirroring the M9 one.)

- [ ] **Step 4: Remote GREEN + verify** — focused 4/4; full `verify` → BUILD SUCCESS, **260 tests**.

- [ ] **Step 5: Commit** — `feat: paginate webhook deliveries with a cursor header` + trailer; push.

---

### Task 7: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M10 — Webhook hardening`; the Webhooks capability row extends: "…; registration and delivery reject SSRF targets (two-layer), merchants redrive failed deliveries, succeeded deliveries age out under retention, listings paginate".
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M10 review

M10 hardened webhook delivery: two-layer SSRF enforcement (registration
400 plus delivery-time revalidation, redirects never followed),
merchant self-serve redrive with a fresh retry cycle, TTL pruning of
succeeded deliveries, and cursor pagination behind a response header.
Known bounds, deliberate:

- **Events are never pruned.** Payload rows accumulate; deliveries are
  the volume multiplier and carry the retention policy.
- **No DNS pinning.** The two-layer check trusts each resolution as it
  happens; pinning registration-time answers would break legitimate
  CDN churn.
- **Loopback http is the only internal exemption** — local receivers,
  and by extension anything that can bind the test host's loopback.
- **Pruning is single-process** like the delivery worker; scale-out
  needs the same SKIP LOCKED treatment.
- **No mTLS or per-endpoint retry budgets** — the signature scheme and
  the shared maxAttempts/backoff policy govern.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 260, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M10 webhook hardening complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V13 public id + delete grant (+ schema/roles) | 1 |
| URL policy rules + registration 400 (+ exception/advice) | 2 |
| Delivery-time revalidation + redirect pin | 3 |
| Redrive route + guarded requeue + deliveryId exposure + 404 vocabulary | 4 |
| RetentionDays property + batched prune + scheduled worker | 5 |
| Keyset walk + Next-Cursor header + limit bounds + unknown-cursor semantics | 6 |
| README + backlog + final gate (260) | 7 |
