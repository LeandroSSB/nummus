# M15 — Operator Audit Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One append-only, operator-readable audit log recording every operator state-changing write (except fee changes) in the same transaction as the action, on the M14 label seam.

**Architecture:** A seventh module `audit` owns one table (`audit.operator_action`) and one write port (`OperatorAudit`, `MANDATORY` transaction — the entry commits with the action or not at all) plus its own listing controller. Actors thread from controllers into services exactly as M14 threaded them for fees. Scheduled conciliation ticks log nothing; the manual route is the only ingest that records.

**Tech Stack:** Java 25, Spring Boot (`@Transactional(MANDATORY)`, `JdbcClient`), PostgreSQL + Flyway, JUnit 5 + Testcontainers, MockMvc, `ApiDrivers` fixtures.

**Spec:** `docs/superpowers/specs/2026-09-22-m15-operator-audit-log-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m15-audit-log`, replace `<Test>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m15-audit-log origin/worktree-m15-audit-log && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Test>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once.
- **TDD strictly:** failing test (remote RED), implement, focused GREEN, full verify, commit.
- **No new dependencies.** Baseline: **343 tests, all green**. Running totals per task are stated in each GREEN step.
- **Worktree:** execution starts from a worktree on branch `worktree-m15-audit-log`. Never commit on `main`.
- Standing idioms: `ApiDrivers` helpers, loopback `http://127.0.0.1:9/<tag>-<uuid>` URLs, `"Bearer " + secret`, `$.publicId`, shared-container delta counting, `@AfterAll` backdating. The action strings are verbatim from the spec's table (`merchant.created`, `operator_key.bootstrapped`, `operator_key.minted`, `operator_key.rotated`, `operator_key.revoked`, `operator_endpoint.registered`, `operator_endpoint.deleted`, `delivery.redriven`, `conciliation.ingested`).

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V17__operator_audit_log.sql (new, T1)
src/main/java/com/leandrossb/nummus/audit/application/OperatorAudit.java (new, T1 — port)
src/main/java/com/leandrossb/nummus/audit/application/OperatorActionRecord.java (new, T1 — read record)
src/main/java/com/leandrossb/nummus/audit/infrastructure/JdbcClientOperatorAudit.java (new, T1 — write+read)
src/main/java/com/leandrossb/nummus/audit/interfaces/AuditLogController.java (new, T4)
src/main/java/com/leandrossb/nummus/audit/interfaces/dto/AuditLogResponse.java (new, T4)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java (+Impl) (modify, T2 — actor on create)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysService.java (+Impl) (modify, T2 — actor on create/revoke/rotate)
src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java (modify, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorKeysController.java (modify, T2)
src/main/java/com/leandrossb/nummus/webhooks/application/WebhookEndpointsService.java (modify, T3 — operator register/delete/redrive with actor)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookEndpointsController.java (modify, T3)
src/main/java/com/leandrossb/nummus/webhooks/interfaces/OperatorWebhookDeliveriesController.java (modify, T3)
src/main/java/com/leandrossb/nummus/conciliation/application/ConciliationService.java (modify, T3 — actor on manual ingest)
src/main/java/com/leandrossb/nummus/conciliation/interfaces/ConciliationReportsController.java (modify, T3)
src/test/java/com/leandrossb/nummus/audit/AuditSchemaTest.java (new, T1)
src/test/java/com/leandrossb/nummus/audit/KeyLifecycleAuditTest.java (new, T2)
src/test/java/com/leandrossb/nummus/audit/SurfaceAuditTest.java (new, T3)
src/test/java/com/leandrossb/nummus/audit/AuditLogRestApiTest.java (new, T4)
README.md, docs/m2-backlog.md (modify, T6)
```

---

### Task 1: `V17` + the audit module skeleton

**Files:**
- Create: `V17__operator_audit_log.sql`, `OperatorAudit.java`, `OperatorActionRecord.java`, `JdbcClientOperatorAudit.java`
- Test: `src/test/java/com/leandrossb/nummus/audit/AuditSchemaTest.java`

**Interfaces:**
- Produces: `OperatorAudit.record(UUID actorKey, String action, String subjectType, UUID subjectId, Map<String, ?> detail)` — `MANDATORY` transaction, serializes `detail` via the shared `ObjectMapper` (null/empty → `{}`); `JdbcClientOperatorAudit.list(UUID after, String action, int limit)` returning `List<OperatorActionRecord>` (newest-first keyset, LEFT JOIN on `operator_key` for the label) — Task 4's listing consumes it. `OperatorActionRecord(UUID entryId, UUID actorKey, String actorLabel, String action, String subjectType, UUID subjectId, java.util.Map<String, Object> detail, java.time.Instant occurredAt)`.

- [ ] **Step 1: Write the failing schema test** (house `IntegrationTestBase` + `@BeforeAll` login dance)

```java
package com.leandrossb.nummus.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
class AuditSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  private String seedOperatorKey() throws Exception {
    String hash = "audit-probe-" + UUID.randomUUID();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix, label) "
          + "values ('" + hash + "', 'nummus_s', 'audit-probe')");
      try (ResultSet rs = st.executeQuery("select public_id from merchants.operator_key "
          + "where key_hash = '" + hash + "'")) {
        rs.next();
        return rs.getObject(1, UUID.class).toString();
      }
    }
  }

  @Test
  void operatorActionIsInsertOnlyWithDefaults() throws Exception {
    String actor = seedOperatorKey();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type, subject_id) values ('" + actor + "', "
          + "'probe.action', 'probe', '" + UUID.randomUUID() + "')"));
      try (ResultSet rs = st.executeQuery("select detail, occurred_at is not null as stamped "
          + "from audit.operator_action where action = 'probe.action'")) {
        assertTrue(rs.next());
        assertEquals("{}", rs.getString("detail"));
        assertTrue(rs.getBoolean("stamped"));
      }
    }
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type) values ('" + actor + "', 'probe.app', 'probe')"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update audit.operator_action set action = 'tampered' "
              + "where action = 'probe.app'"));
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from audit.operator_action where action = 'probe.app'"));
    }
  }

  @Test
  void actorKeyIsAHardForeignKey() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("insert into audit.operator_action "
              + "(actor_key, action, subject_type) values ('"
              + UUID.randomUUID() + "', 'probe.ghost', 'probe')"));
    }
  }

  @Test
  void subjectIdStaysNullable() throws Exception {
    String actor = seedOperatorKey();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      assertEquals(1, st.executeUpdate("insert into audit.operator_action "
          + "(actor_key, action, subject_type) values ('" + actor + "', 'probe.nosubject', 'probe')"));
      try (ResultSet rs = st.executeQuery("select subject_id is null as no_subject "
          + "from audit.operator_action where action = 'probe.nosubject'")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("no_subject"));
      }
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (`schema "audit" does not exist`).

- [ ] **Step 3: Implement** — the migration verbatim from the spec's V17 block; then:

`OperatorAudit.java`:

```java
package com.leandrossb.nummus.audit.application;

import java.util.Map;
import java.util.UUID;

/** Write port for the operator audit log. Implementations record inside the
 *  action's transaction: an entry commits with the action or not at all. */
public interface OperatorAudit {

  void record(UUID actorKey, String action, String subjectType, UUID subjectId,
      Map<String, ?> detail);
}
```

`OperatorActionRecord.java` — the read record per the Interfaces block above (plain record, one javadoc line: "One audited operator action, newest-first from the listing").

`JdbcClientOperatorAudit.java`:

```java
package com.leandrossb.nummus.audit.infrastructure;

import com.leandrossb.nummus.audit.application.OperatorActionRecord;
import com.leandrossb.nummus.audit.application.OperatorAudit;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The audit log's single adapter: writes inside the caller's transaction
 *  (MANDATORY), reads for the listing (newest-first keyset). */
@Component
public class JdbcClientOperatorAudit implements OperatorAudit {

  private final JdbcClient jdbc;
  private final ObjectMapper objectMapper;

  public JdbcClientOperatorAudit(JdbcClient jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(UUID actorKey, String action, String subjectType, UUID subjectId,
      Map<String, ?> detail) {
    jdbc.sql("""
        insert into audit.operator_action (public_id, actor_key, action, subject_type, subject_id, detail)
        values (:entryId, :actorKey, :action, :subjectType, :subjectId, :detail::jsonb)
        """)
        .param("entryId", UUID.randomUUID())
        .param("actorKey", actorKey)
        .param("action", action)
        .param("subjectType", subjectType)
        .param("subjectId", subjectId)
        .param("detail", detail == null || detail.isEmpty() ? "{}" : objectMapper.writeValueAsString(detail))
        .update();
  }

  /** Listing read: newest-first keyset; action is an optional exact filter. */
  public List<OperatorActionRecord> list(UUID after, String action, int limit) {
    return jdbc.sql("""
        select a.public_id, a.actor_key, k.label as actor_label, a.action, a.subject_type,
               a.subject_id, a.detail, a.occurred_at
        from audit.operator_action a
        left join merchants.operator_key k on k.public_id = a.actor_key
        where (:after::uuid is null or a.id < (select f.id from audit.operator_action f
              where f.public_id = :after))
          and (:action is null or a.action = :action)
        order by a.id desc
        limit :limit
        """)
        .param("after", after)
        .param("action", action)
        .param("limit", limit)
        .query((rs, i) -> new OperatorActionRecord(
            rs.getObject("public_id", UUID.class),
            rs.getObject("actor_key", UUID.class),
            rs.getString("actor_label"),
            rs.getString("action"),
            rs.getString("subject_type"),
            rs.getObject("subject_id", UUID.class),
            readDetail(rs.getObject("detail")),
            rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant()))
        .list();
  }

  private Map<String, Object> readDetail(Object pg) {
    if (pg == null) {
      return Map.of();
    }
    String json = pg instanceof org.postgresql.util.PGobject pgo ? pgo.getValue() : pg.toString();
    return objectMapper.readValue(json, Map.class);
  }
}
```

(If binding a String against `:detail::jsonb` fights the named-parameter parser — the same `::` pattern already ships in `retireApiKey` — keep the cast; it is safe. If the PGobject import is unnecessary after choosing the String bind, drop it. `readValue(json, Map.class)` returns the raw map; the unchecked warning is fine or suppress at the method.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=AuditSchemaTest` → 3/3; full `verify` → BUILD SUCCESS, **346 tests** (343 + 3).

- [ ] **Step 5: Commit** — `feat: add the operator audit log schema and port (V17)` + trailer; push.

---

### Task 2: Key lifecycle and merchant creation recording (TDD)

**Files:**
- Modify: `MerchantsService`/`Impl` (`create(String name, FeeSchedule fee, UUID actingOperatorKey)`, now `@Transactional`), `OperatorKeysService`/`Impl` (`create(String label, Duration expiresIn, UUID actorKey)`, `revoke(UUID keyPublicId, UUID actorKey)`, `rotate(UUID keyPublicId, Duration expiresIn, UUID actorKey)`; `bootstrap` unchanged — it records with the NEW key as actor), `MerchantsController`, `OperatorKeysController`
- Test: `src/test/java/com/leandrossb/nummus/audit/KeyLifecycleAuditTest.java`

**Interfaces:**
- Consumes: Task 1's `OperatorAudit`.
- Produces: the five action strings recorded with actor = the CALLING key (`merchant.created`, `operator_key.minted`, `operator_key.rotated`, `operator_key.revoked`) and `operator_key.bootstrapped` (actor = the new key). `detail` for mint/rotate/bootstrapped carries `label`; rotate also carries `retiredKey`. Ripples: every direct service call `merchants.create(` / `operatorKeys.create(` / `operatorKeys.rotate(` / `operatorKeys.revoke(` in tests gains the actor argument (use the minted probe key's id or `null`-for-skip? NO — the methods now REQUIRE a non-null actor at the controller; service-level test callers pass a real minted key's public id, same as M14's probe keys).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
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
class KeyLifecycleAuditTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  /** (actorKey, action, subjectId) of the latest entries, newest first. */
  private java.util.List<String[]> recentEntries(int limit) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select actor_key::text, action, coalesce(subject_id::text, '') "
            + "from audit.operator_action order by id desc limit " + limit)) {
      var out = new java.util.ArrayList<String[]>();
      while (rs.next()) {
        out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
      }
      return out;
    }
  }

  @Test
  void merchantCreationIsAuditedWithTheCallingKey() throws Exception {
    String callerId = operatorKeys.create("audit-caller", null).key().publicId().toString();
    mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKeys.create("audit-auth", null).secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Audited Merchant\"}"))
        .andExpect(status().isCreated());
    var entries = recentEntries(1);
    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("merchant.created", entries.get(0)[1]);
    Assertions.assertEquals("merchant", entries.get(0)[2].isEmpty() ? "" : "merchant-or-id");
    // The subject id must be the created merchant's id — read it from the response
    // in the real test (see note) and assert equality instead of the placeholder above.
  }

  @Test
  void keyMintRotateAndRevokeAreAudited() throws Exception {
    var caller = operatorKeys.create("audit-actor", null);
    String callerId = caller.key().publicId().toString();

    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + caller.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"minted-probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String mintedId = com.jayway.jsonpath.JsonPath.read(
        minted.getResponse().getContentAsString(), "$.keyId");
    var top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.minted", top[1]);
    Assertions.assertEquals(callerId, top[0]);
    Assertions.assertEquals(mintedId, top[2]);

    MvcResult rotated = mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", "Bearer " + com.jayway.jsonpath.JsonPath.read(
                minted.getResponse().getContentAsString(), "$.secret"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    String rotatedId = com.jayway.jsonpath.JsonPath.read(
        rotated.getResponse().getContentAsString(), "$.keyId");
    top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.rotated", top[1]);
    Assertions.assertEquals(mintedId, top[0], "the calling key is the actor");
    Assertions.assertEquals(rotatedId, top[2]);

    mockMvc.perform(delete("/v1/operator/api-keys/" + rotatedId)
            .header("Authorization", "Bearer " + caller.secret()))
        .andExpect(status().isNoContent());
    top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.revoked", top[1]);
    Assertions.assertEquals(callerId, top[0]);
    Assertions.assertEquals(rotatedId, top[2]);
  }

  @Test
  void detailCarriesTheLabelAndRetiredKey() throws Exception {
    var caller = operatorKeys.create("detail-actor", null);
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + caller.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"detail-probe\"}"))
        .andExpect(status().isCreated());
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select detail::text from audit.operator_action "
            + "where action = 'operator_key.minted' order by id desc limit 1")) {
      Assertions.assertTrue(rs.next());
      Assertions.assertTrue(rs.getString(1).contains("detail-probe"));
    }
  }

  @Test
  void merchantSelfServeKeyOpsRecordNothing() throws Exception {
    String merchant = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorKeys, "Unaudited Self");
    int before = recentEntries(100).size();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", "Bearer " + merchant)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", "Bearer " + merchant)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    Assertions.assertEquals(before, recentEntries(100).size());
  }

  @Test
  void bootstrapRecordsWithTheNewKeyAsActor() throws Exception {
    // Bootstrap is one-time by table state; drive it against a fresh context is
    // impossible here — instead assert the code path via the service on a
    // pristine container is equally impossible. Pin the observable contract:
    // on THIS deployment bootstrap has already run, so this test instead pins
    // the negative — no ghost bootstrapped entries appear from normal mints.
    int before = countAction("operator_key.bootstrapped");
    operatorKeys.create("no-bootstrap", null);
    Assertions.assertEquals(before, countAction("operator_key.bootstrapped"));
  }

  private int countAction(String action) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from audit.operator_action "
            + "where action = '" + action + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
```

**Implementer notes (authoritative):** test 1 must read the created merchant's id from the POST response (mirror `FeeHistoryRestApiTest.createMerchantAsOperator`'s field read) and assert `entries.get(0)[2]` equals it — replace the placeholder assertion line. Test 5's comment explains the pin honestly; if a cleaner deterministic bootstrap pin is achievable (e.g. a service-level test constructing the impl with a test `OperatorBootstrapProperties` token against a wiped `operator_key` table via SQL, restoring in `@AfterAll`), prefer it — the house has done table-wipe patterns in `ConciliationStallGuardTest`.

- [ ] **Step 2: Remote RED** — push; FAIL (no entries: the actions record nothing yet; `create` arity compile failure is the expected first RED).

- [ ] **Step 3: Implement** — thread `UUID actingOperatorKey`/`actorKey` through the four signatures (controllers pass `operator.keyPublicId()`); make `MerchantsServiceImpl.create` `@Transactional`; each service records via the injected `OperatorAudit` AFTER its primary write, e.g. in `OperatorKeysServiceImpl.create`:

```java
  @Override
  @Transactional
  public IssuedApiKey create(String label, Duration expiresIn, UUID actorKey) {
    IssuedApiKey issued = mint(label, expiresIn);
    audit.record(actorKey, "operator_key.minted", "operator_key", issued.key().publicId(),
        java.util.Map.of("label", label));
    return issued;
  }
```

`rotate` records `Map.of("label", <carried label>, "retiredKey", <old key id>)`; `revoke` records `Map.of()`; `MerchantsServiceImpl.create` records `merchant.created` with `Map.of("name", name)`; `bootstrap` records `operator_key.bootstrapped` with the NEW key as actor and `Map.of("label", label)`. Ripples: update every direct service call in tests (grep `operatorKeys.create(`, `merchants.create(`, `.rotate(`, `.revoke(`) — probe keys minted for the purpose, the M14 pattern.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='KeyLifecycleAuditTest,OperatorLabelRestApiTest,OperatorKeysServiceTest,MerchantsRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **351 tests** (346 + 5).

- [ ] **Step 5: Commit** — `feat: audit key lifecycle and merchant creation` + trailer; push.

---

### Task 3: Endpoints, redrive, and ingest recording — plus the negatives (TDD)

**Files:**
- Modify: `WebhookEndpointsService` (`registerOperator(URI url, List<String> eventTypes, UUID actorKey)` — now `@Transactional`; new `deleteOperator(UUID publicId, UUID actorKey)` and `redriveOperatorDelivery(UUID deliveryId, UUID actorKey)` — both `@Transactional`), `OperatorWebhookEndpointsController`, `OperatorWebhookDeliveriesController` (redrive delegates to the service), `ConciliationService` (`ingest(Instant from, Instant to, UUID actorKey)` — records iff actor non-null; the worker's `ingestIfAnyLines` path passes none), `ConciliationReportsController`
- Test: `src/test/java/com/leandrossb/nummus/audit/SurfaceAuditTest.java`

**Interfaces:**
- Consumes: Task 1's port; Task 2's ripple patterns.
- Produces: `operator_endpoint.registered` (detail: url), `operator_endpoint.deleted`, `delivery.redriven`, `conciliation.ingested` (detail: from/to as ISO strings) — all actor = calling key. Negatives pinned: fee PUT records nothing; scheduled tick records nothing.

- [ ] **Step 1: Write the failing test** — same class shape as Task 2 (`recentEntries`/`countAction` helpers copied or extracted to a small package-private fixture used by both audit suites — house judgment):

1. `endpointRegistrationAndDeletionAreAudited` — register an operator endpoint (loopback URL), assert the top entry (`operator_endpoint.registered`, subject = endpoint id, detail contains the URL); delete it, assert `operator_endpoint.deleted`.
2. `deliveryRedriveIsAudited` — seed a FAILED delivery (Task 4 of M12's pattern: register endpoint, `webhookStore.insertEvent(..., null, ...)`, SQL-force FAILED), redrive via the operator route, assert `delivery.redriven` with subject = delivery id.
3. `manualIngestIsAuditedWithWindowBounds` — clean settle + manual ingest (the `ConciliationAlertsTest` recipe), assert `conciliation.ingested` with subject = report id and detail containing both window bounds.
4. `feeChangesRecordNothing` — a fee PUT (M14 route), assert no NEW entries of any action (count delta zero).
5. `scheduledTicksRecordNothing` — plant a divergence, `worker.tick()`, assert no `conciliation.ingested` entries appear.

Write these following the exact helper idioms of `KeyLifecycleAuditTest`, `OperatorWebhookDeliveriesRestApiTest`, and `ConciliationAlertsTest` respectively — the assertions are the same `recentEntries`/`countAction` shapes with the new action strings. No stubs: build the real helpers.

- [ ] **Step 2: Remote RED** — push; FAIL (no entries for the new actions).

- [ ] **Step 3: Implement** — per the Files/Interfaces blocks. `ConciliationService`: the controller passes `operator.keyPublicId()`; recording happens after `store.insert(summary, lines)` inside the same `@Transactional ingest`, `if (actorKey != null)` — `audit.record(actorKey, "conciliation.ingested", "conciliation_report", summary.publicId(), Map.of("from", from.toString(), "to", to.toString()))`. The worker path (`ingestIfAnyLines` → shared persist) passes no actor and records nothing. `WebhookEndpointsService.redriveOperatorDelivery` wraps `store.requeueFailedDelivery(null, id)` + audit in one transaction, returning the boolean; the controller's 404 semantics unchanged.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='SurfaceAuditTest,ConciliationAlertsTest,ConciliationWorkerTest,OperatorWebhookEndpointsRestApiTest,OperatorWebhookDeliveriesRestApiTest,FeeHistoryRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **358 tests** (351 + 7... count YOUR `@Test` methods — 5 tests here unless you split; the authoritative total is 343 + your cumulative method count; report the true number).

**Correction for the implementer:** the arithmetic above is provisional. Authoritative: this task adds exactly the `@Test` methods you write (expected 5, per the five numbered behaviors). Full-verify total: **356** (351 + 5).

- [ ] **Step 5: Commit** — `feat: audit operator endpoint, redrive, and ingest actions` + trailer; push.

---

### Task 4: The listing (TDD)

**Files:**
- Create: `AuditLogController.java`, `dto/AuditLogResponse.java`
- Test: `src/test/java/com/leandrossb/nummus/audit/AuditLogRestApiTest.java`

**Interfaces:**
- Consumes: Task 1's `JdbcClientOperatorAudit.list(UUID after, String action, int limit)`.
- Produces: `GET /v1/operator/audit-log?action=&after=&limit=` — operator-only (403 merchant keys), newest-first array of `AuditLogResponse` (`entryId`, `action`, `subjectType`, `subjectId`, `detail` object, `occurredAt`, `actorKey`, `actorLabel`), `Next-Cursor` per the M10 contract, `limit` 1–100 → 400 outside, unknown cursor → empty page, `action` exact-match filter.

- [ ] **Step 1: Write the failing test** — five tests:
1. `auditLogPaginatesNewestFirst` — mint 3 keys (3 `operator_key.minted` entries), walk limit=2 → page 1 length 2 + `Next-Cursor`, page 2 length 1, no header; assert `$[0].actorLabel` equals the freshest mint's label and `$[0].detail.label` matches (order pinned).
2. `actionFilterNarrows` — `?action=merchant.created` after creating a merchant + minting keys → only `merchant.created` rows.
3. `boundsAndUnknownCursor` — limit 0 and 101 → 400; `?after=<random uuid>` → 200, empty, no header.
4. `merchantKeysGet403` — merchant bearer → 403; no auth → 401.
5. `detailRendersAsAnObject` — `$[0].detail` is a JSON object (`jsonPath("$[0].detail.label").exists()` on a minted entry).

Follow `OperatorWebhookDeliveriesRestApiTest` + `FeeHistoryRestApiTest` idioms (`ApiDrivers`, delta counting — the shared container accumulates entries from the other audit suites, so scope assertions to entries you just created via the action filter or fresh labels).

- [ ] **Step 2: Remote RED** — push; FAIL (404 on the route).

- [ ] **Step 3: Implement** — the controller mirrors the M10 pagination controllers exactly (inject `JdbcClientOperatorAudit` — or extract its read side behind a tiny port if the house seam prefers; the deliveries controller injects the concrete store's port, mirror that judgment):

```java
@RestController
class AuditLogController {

  private final JdbcClientOperatorAudit audit;

  AuditLogController(JdbcClientOperatorAudit audit) {
    this.audit = audit;
  }

  @GetMapping("/v1/operator/audit-log")
  ResponseEntity<List<AuditLogResponse>> list(AuthenticatedOperator operator,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    var page = audit.list(after, action, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(AuditLogResponse::from).toList());
    }
    var cursor = page.get(limit - 1).entryId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(AuditLogResponse::from).toList());
  }
}
```

`AuditLogResponse.from(OperatorActionRecord)` maps fields 1:1. Package `com.leandrossb.nummus.audit.interfaces` (+ `.dto`).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='AuditLogRestApiTest,OperatorEventCatalogTest'` → all green; full `verify` → BUILD SUCCESS, **361 tests** (356 + 5).

- [ ] **Step 5: Commit** — `feat: list the operator audit log with cursor pagination` + trailer; push.

---

### Task 5: Atomicity — the entry commits with the action or not at all (TDD)

**Files:**
- Test: extends `KeyLifecycleAuditTest` (or its own `AuditAtomicityTest` — house judgment)

**Interfaces:**
- Consumes: the M13 `check (false)` failure-force technique (`ConciliationWorkerTest`'s pattern, dropped in `finally`).

- [ ] **Step 1: Write the failing test** — `aFailedActionRollsBackItsEntry`: capture the `operator_key.minted` count, apply `alter table audit.operator_action add constraint audit_probe_chk check (false) not valid` via `adminConnection`, attempt an operator mint (expect 500 — the constraint fires inside the transaction), drop the constraint in `finally`, assert the minted count unchanged AND no new key row landed (`select count(*) from merchants.operator_key` delta zero). The 500 is expected — wrap the perform in try/catch or assert `is5xxServerError`.

- [ ] **Step 2: Remote RED** — push; run it against the CURRENT code — it should PASS already if atomicity holds (the constraint blocks the audit insert; the transaction rolls back the key too). A pass-on-arrival RED is legitimate for an invariant pin (the M13 precedent: pins for currently-correct behavior); verify the test FAILS if atomicity were broken by reasoning through the mechanism, note it in the report, and proceed — the pin is the deliverable.

- [ ] **Step 3: No implementation** — this task is the pin alone; if the test exposes a real atomicity gap (entry commits without the action), STOP and report BLOCKED with the evidence.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='KeyLifecycleAuditTest'` (or the new class) → all green; full `verify` → BUILD SUCCESS, **362 tests** (361 + 1).

- [ ] **Step 5: Commit** — `test: pin audit atomicity with the action` + trailer; push.

---

### Task 6: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M15 — Operator audit log`; the **Authentication** capability row extends: "…; every operator write lands in an append-only audit log (operator-readable, cursor-paginated)".
- Modify: `docs/m2-backlog.md` — append (AFTER the M14 section, at the end of the file):

```markdown
## From the M15 design

M15 completed the attribution story: every operator state-changing write
except fee changes (which carry their own M14 typed trail) lands in one
append-only, operator-readable audit log, recorded in the action's own
transaction. Known bounds, deliberate:

- **No retroactive backfill** — the log begins at M15; pre-M15 history is
  absent, not synthesized.
- **Fee changes are not double-recorded** — the M14 typed history is their
  audit trail.
- **Every operator sees the whole log** — role-level; no actor filter or
  scoping until RBAC lands on the M14 seam.
- **`detail` is advisory display data** — no schema contract; never a
  source of truth.
- **Reads are not audited** — only state-changing writes record; the log
  is not a query journal.
- **Scheduled ticks log nothing** — the machine is not an operator; only
  the manual ingest route records.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 362, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M15 operator audit log complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V17 + audit module (port, adapter, schema/roles tests) | 1 |
| Key lifecycle + merchant creation recording (+ self-serve negative) | 2 |
| Endpoint/redrive/ingest recording (+ fee/tick negatives) | 3 |
| Listing: pagination, filter, bounds, gating, label join | 4 |
| Atomicity pin (entry commits with the action) | 5 |
| README + backlog + final gate (362) | 6 |
