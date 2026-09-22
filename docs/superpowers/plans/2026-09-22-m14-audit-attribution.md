# M14 — Audit Attribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operator keys gain an immutable identity label (who minted, who acted), and fee-schedule changes become attributed append-only history with a cached current value — plus an operator-only, cursor-paginated history listing.

**Architecture:** `operator_key.label` (non-null, `system` backfill, immutable) is the identity; attribution records the acting key's public id and joins the label at read. `merchants.fee_schedule_entry` is write-once history; `PUT /v1/merchants/{id}/fee` appends an entry and updates the merchant's `fee_rate`/`fee_fixed` cache in one transaction — settlement and quotes read the cache unchanged. The M10 pagination contract governs the history listing.

**Tech Stack:** Java 25, Spring Boot (records, `@ConfigurationProperties` untouched), PostgreSQL via `JdbcClient` + Flyway, JUnit 5 + Testcontainers, MockMvc, `ApiDrivers` test fixtures.

**Spec:** `docs/superpowers/specs/2026-09-22-m14-audit-attribution-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m14-attribution`, replace `<Test>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m14-attribution origin/worktree-m14-attribution && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Test>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once.
- **TDD strictly:** failing test (remote RED), implement, focused GREEN, full verify, commit.
- **No new dependencies.** Baseline: **329 tests, all green** on `main` at plan-writing time. Running totals per task are stated in each GREEN step; if a fix wave changes a count, the controller amends the remaining briefs.
- **Worktree:** execution starts from a worktree on branch `worktree-m14-attribution`. Never commit on `main`.
- Standing test idioms: `ApiDrivers` helpers (`operatorAuth`, `createMerchantAndGetKey`, `loopbackUrl`), loopback `http://127.0.0.1:9/<tag>-<uuid>` URLs, `"Bearer " + secret`, `$.publicId`, shared-container delta counting and `@AfterAll` backdating.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V16__operator_labels_and_fee_history.sql (new, Task 1)
src/main/java/com/leandrossb/nummus/merchants/domain/ApiKey.java                (modify, T2 — nullable label)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysService.java (modify, T2)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysServiceImpl.java (modify, T2)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java    (modify, T2/T3/T4)
src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java (modify, T2/T3/T4)
src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorKeysController.java (modify, T2 — label on mint/rotate)
src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorBootstrapController.java (modify, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateKeyRequest.java (modify, T2 — label)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyRequest.java (modify, T2 — no change needed if rotate reads the calling key's label; verify)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/ApiKeyResponse.java (modify, T2 — nullable label)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateKeyResponse.java (modify, T2 — label)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RotateKeyResponse.java (modify, T2 — label)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java  (modify, T3 — attributed update)
src/main/java/com/leandrossb/nummus/merchants/application/MerchantsServiceImpl.java (modify, T3)
src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java (modify, T3/T4)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/FeeHistoryResponse.java (new, T4)
src/test/java/com/leandrossb/nummus/merchants/AttributionSchemaTest.java         (new, T1)
src/test/java/com/leandrossb/nummus/merchants/OperatorLabelRestApiTest.java      (new, T2)
src/test/java/com/leandrossb/nummus/merchants/FeeHistoryRestApiTest.java         (new, T3/T4)
README.md, docs/m2-backlog.md                                                   (modify, T5)
```

---

### Task 1: `V16` — operator labels + fee history table

**Files:**
- Create: `src/main/resources/db/migration/V16__operator_labels_and_fee_history.sql`
- Test: `src/test/java/com/leandrossb/nummus/merchants/AttributionSchemaTest.java`

**Interfaces:**
- Produces: `merchants.operator_key.label text not null default 'system'` (existing keys backfilled); `merchants.fee_schedule_entry` (columns per the spec's DDL, nullable `created_by` FK, `(merchant_id, id desc)` index, select+insert grants for `nummus_app`); one backfill seed entry per existing merchant with `created_by = NULL`. Tasks 2–4 depend on all of it.

- [ ] **Step 1: Write the failing schema test** (house `IntegrationTestBase` + `adminConnection()`/`appConnection()` + `@BeforeAll` login dance idioms)

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class AttributionSchemaTest extends IntegrationTestBase {

  @BeforeAll
  void enableLogin() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.execute("alter role nummus_app login password '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void operatorKeyLabelIsNonNullWithSystemDefault() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.operator_key (key_hash, prefix) "
          + "values ('label-probe-1', 'nummus_s')");
      try (ResultSet rs = st.executeQuery("select label from merchants.operator_key "
          + "where key_hash = 'label-probe-1'")) {
        assertTrue(rs.next());
        assertEquals("system", rs.getString("label"));
      }
    }
  }

  @Test
  void feeScheduleEntryIsInsertOnlyWithNullableAttribution() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name) values ('"
          + merchantId + "', 'history probe')");
      assertEquals(1, st.executeUpdate("insert into merchants.fee_schedule_entry "
          + "(merchant_id, rate, fixed, created_by) select id, 0.01, 0.25, null "
          + "from merchants.merchant where public_id = '" + merchantId + "'"));
    }
    // The app role can SELECT and INSERT but holds no UPDATE or DELETE.
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry "
          + "where created_by is null")) {
        assertTrue(rs.next());
      }
      st.executeUpdate("insert into merchants.fee_schedule_entry "
          + "(merchant_id, rate, fixed, created_by) select id, 0.02, 0.50, null "
          + "from merchants.merchant where public_id = '" + merchantId + "'");
      org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("update merchants.fee_schedule_entry set rate = 0.99 "
              + "where created_by is null"));
      org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () ->
          st.executeUpdate("delete from merchants.fee_schedule_entry where rate = 0.02"));
    }
  }

  @Test
  void existingMerchantsCarryExactlyOneSeedEntryWithNullAttribution() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("insert into merchants.merchant (public_id, name, fee_rate, fee_fixed) "
          + "values ('" + merchantId + "', 'seed probe', 0.0123, 1.75)");
    }
    // V16's backfill ran at migration time, before this row existed — a merchant
    // created after migration has no seed until its first fee PUT. So assert the
    // backfill against a PRE-EXISTING merchant instead: the seed merchant.
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '11111111-1111-4111-8111-111111111111'")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select e.created_by, e.rate, e.fixed "
            + "from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '11111111-1111-4111-8111-111111111111'")) {
      assertTrue(rs.next());
      assertEquals(null, rs.getObject("created_by"));
    }
    assertNotNull(merchantId); // silence unused
  }
}
```

(The seed merchant `11111111-1111-…` exists from V10 with zero fees — its backfill entry carries `rate = 0, fixed = 0`; assert `created_by` NULL and count 1. Drop the unused local if it reads cleaner.)

- [ ] **Step 2: Remote RED** — push; FAIL (`column "label" does not exist`; `relation "merchants.fee_schedule_entry" does not exist`).

- [ ] **Step 3: Write the migration**

```sql
-- M14 audit attribution: operator keys gain an immutable identity label
-- (existing keys backfill as 'system'), and fee-schedule changes become
-- attributed, append-only history. The merchant's fee_rate/fee_fixed stay
-- the cached current value; each change appends one entry naming the acting
-- operator key. created_by is nullable: NULL is the pre-attribution
-- sentinel for state whose actor predates identity — rendered 'system'.

alter table merchants.operator_key
  add column label text not null default 'system';

create table merchants.fee_schedule_entry (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  rate        numeric(9,6) not null,
  fixed       numeric(19,4) not null,
  valid_from  timestamptz not null default now(),
  created_by  uuid references merchants.operator_key(public_id),
  created_at  timestamptz not null default now()
);
create index fee_schedule_entry_merchant_idx
  on merchants.fee_schedule_entry (merchant_id, id desc);

insert into merchants.fee_schedule_entry (merchant_id, rate, fixed, created_by)
select id, fee_rate, fee_fixed, null from merchants.merchant;

grant select, insert on merchants.fee_schedule_entry to nummus_app;
```

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest=AttributionSchemaTest` → 3/3; full `verify` → BUILD SUCCESS, **332 tests** (329 + 3).

- [ ] **Step 5: Commit** — `feat: add operator labels and fee history schema (V16)` + trailer; push.

---

### Task 2: Labels on mint, rotate, bootstrap, and listings (TDD)

**Files:**
- Modify: `ApiKey` (domain — nullable `label` last), `OperatorKeysService`/`Impl`, `MerchantStore` (+impl: `insertOperatorKey` gains label; `findOperatorKeyLabel(UUID)`), `OperatorKeysController`, `OperatorBootstrapController`, `CreateKeyRequest` (+`label`), `ApiKeyResponse`/`CreateKeyResponse`/`RotateKeyResponse` (+nullable `label`)
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorLabelRestApiTest.java`

**Interfaces:**
- Produces: `OperatorKeysService.create(String label, Duration expiresIn)`, `rotate(UUID keyPublicId, Duration expiresIn)` (label carried from the calling key internally), `bootstrap(String token, String label)`; `ApiKey(UUID publicId, String prefix, String status, Instant createdAt, Instant expiresAt, Instant lastUsedAt, String label)` — label nullable, merchant keys pass null; `MerchantStore.findOperatorKeyLabel(UUID keyPublicId)` returning `Optional<String>`; label validation shared: `static void requireValidLabel(String label)` in `OperatorKeysServiceImpl` (1–64 chars after trim, non-blank) throwing `InvalidOperatorLabelException` (`merchants.application`, → 400).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class OperatorLabelRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void operatorMintRequiresAValidLabelAndEchoesIt() throws Exception {
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"   \",\"expiresIn\":\"P1D\"}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"" + "x".repeat(65) + "\"}"))
        .andExpect(status().isBadRequest());
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"ci-runner\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("ci-runner"))
        .andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    MvcResult listed = mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk()).andReturn();
    org.junit.jupiter.api.Assertions.assertTrue(
        listed.getResponse().getContentAsString().contains(keyId));
  }

  @Test
  void rotationCarriesTheCallingKeysLabelForward() throws Exception {
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"on-call\"}"))
        .andExpect(status().isCreated()).andReturn();
    String secret = com.jayway.jsonpath.JsonPath.read(
        minted.getResponse().getContentAsString(), "$.secret");
    mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", "Bearer " + secret)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.label").value("on-call"));
  }

  @Test
  void bootstrapRequiresALabel() throws Exception {
    // The bootstrap is one-time by table state; this test only pins the
    // label requirement, which fires BEFORE the one-time check? No — keep
    // house order: validation of the request body happens at the controller
    // via the shared requireValidLabel BEFORE bootstrap() is invoked, so
    // even an already-bootstrapped deployment 400s on a missing label
    // without touching bootstrap state. Assert the 400 shape only.
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void merchantKeyListingIsUnaffectedByTheNullableLabel() throws Exception {
    // The shared ApiKeyResponse gains a nullable label; merchant listings
    // still succeed (labels render null there).
    String merchant = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorKeys, "Label Merchant");
    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", "Bearer " + merchant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].label").doesNotExist());
  }
}
```

(The bootstrap test's 400 must come from label validation ordered before the bootstrap-state check — implement Step 3 accordingly. If the house `GlobalExceptionHandler` maps `IllegalArgumentException` to 400, `InvalidOperatorLabelException` may simply extend it or get its own handler mirroring `InvalidKeyExpiryException`; follow whichever reads cleaner. The merchant-listing assertion depends on the serializer omitting nulls — check `EndpointResponse`/DTO conventions; if nulls serialize as `null`, assert `jsonPath("$[0].label").value((String) null)` instead.)

- [ ] **Step 2: Remote RED** — push; FAIL (mint without label returns 201; no `$.label` field).

- [ ] **Step 3: Implement**

- `InvalidOperatorLabelException` (`merchants.application`, mirrors `InvalidKeyExpiryException`): message `"label is required (1-64 characters)"`; mapped 400 in `GlobalExceptionHandler` next to `invalidKeyExpiry`.
- `OperatorKeysServiceImpl`: `requireValidLabel(String label)` — null/blank/length>64 after trim → throw. `mint(String label, Duration expiresIn)` validates label first, then expiry, and passes the label to `store.insertOperatorKey(keyHash, prefix, expiresIn, label)`. `create(String label, Duration expiresIn)`; `rotate` looks up the calling key's label via `store.findOperatorKeyLabel(keyPublicId)` (empty → `UnknownApiKeyException`) and mints with it; `bootstrap(String token, String label)` validates the label first, then the existing token/state checks, then mints.
- `MerchantStore.insertOperatorKey(String keyHash, String prefix, Duration expiresIn, String label)`; `Optional<String> findOperatorKeyLabel(UUID keyPublicId)` (`select label from merchants.operator_key where public_id = :id`). `listOperatorKeys`/`findActiveOperatorKeyByHash`/`findActiveKeyByHash`(merchant! — merchant keys pass a NULL label into the shared `ApiKey` mapping; add `label` to their SELECTs as a literal `null` or restructure per-column mapping — implementer's cleanest choice, keeping the record's merchant construction sites compiling with `null`).
- Domain `ApiKey` gains trailing `String label` (nullable) — update every `new ApiKey(` construction site (grep; merchant paths pass `null`).
- Controllers: `OperatorKeysController.createKey` reads `request.label()` (required — absent → the service throws 400); `OperatorBootstrapController` request record gains `label`, validated before `bootstrap(...)` is called. `CreateKeyResponse`/`RotateKeyResponse`/`ApiKeyResponse` gain nullable `label` mapped from `issued.key().label()` / `key.label()`.
- Ripples: grep `operatorKeys.create(` and `new ApiKey(` across tests — service-level calls gain the label argument (use `"probe"` or `"test"`); `OperatorKeysServiceTest` constructions updated.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='OperatorLabelRestApiTest,OperatorKeysServiceTest,OperatorAuthRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **336 tests** (332 + 4).

- [ ] **Step 5: Commit** — `feat: label operator keys with an immutable identity` + trailer; push.

---

### Task 3: Attributed fee changes — append + cache (TDD)

**Files:**
- Modify: `MerchantsService`/`Impl` (`updateFeeSchedule(UUID merchantPublicId, FeeSchedule fee, UUID actingOperatorKey)`), `MerchantStore` (+impl: `insertFeeScheduleEntry(UUID merchantPublicId, FeeSchedule fee, UUID createdBy)`), `MerchantsController.updateFee` (passes `operator.keyPublicId()`)
- Test: `src/test/java/com/leandrossb/nummus/merchants/FeeHistoryRestApiTest.java` (first three tests; Task 4 adds the listing tests to the same class)

**Interfaces:**
- Consumes: Task 1's table; Task 2's labelled keys.
- Produces: `updateFeeSchedule` is `@Transactional` and does BOTH the entry append and the cache update; `MerchantStore.insertFeeScheduleEntry(UUID merchantPublicId, FeeSchedule fee, UUID createdBy)` resolves the merchant by public id (empty → `UnknownMerchantException` semantics preserved by the existing update guard). The PUT response is unchanged.

- [ ] **Step 1: Write the failing tests** (same class continues in Task 4)

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class FeeHistoryRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String createMerchant(String name) throws Exception {
    MvcResult created = mockMvc.perform(
            com.leandrossb.nummus.testutils.ApiDrivers.hasOperator(() ->
                ApiDrivers.operatorAuth(operatorKeys)))
        .andReturn(); // placeholder — see implementer note
    return null;
  }

  private int entryCount(String keyPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry e "
            + "join merchants.operator_key k on k.public_id = e.created_by "
            + "where k.public_id = '" + keyPublicId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private int entryCountForMerchant(String merchantPublicId) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from merchants.fee_schedule_entry e "
            + "join merchants.merchant m on m.id = e.merchant_id "
            + "where m.public_id = '" + merchantPublicId + "'")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void feePutAppendsAnAttributedEntryAndUpdatesTheCacheTogether() throws Exception {
    String merchantId = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorKeys, "History A");
    MvcResult merchant = mockMvc.perform(get("/v1/merchants/" + merchantId))
        .andExpect(status().isOk()).andReturn();
    // merchantId above is the KEY secret — createMerchantAndGetKey returns the
    // api key secret, not the merchant id. Resolve the id via the operator listing
    // or create via /v1/merchants and read $.merchantId (implementer note below).
    throw new UnsupportedOperationException("implement per the note");
  }

  @Test
  void aNoOpFeeChangeStillAppends() throws Exception {
    throw new UnsupportedOperationException("implement per the note");
  }

  @Test
  void aSecondChangeYieldsTwoEntriesNewestFirst() throws Exception {
    throw new UnsupportedOperationException("implement per the note");
  }
}
```

**Implementer note (authoritative — the stubs above are shape only):** `ApiDrivers.createMerchantAndGetKey` returns the merchant's API-key SECRET. For these tests you need the merchant's PUBLIC ID plus an operator key whose PUBLIC ID you can attribute. Build helpers on the existing idioms:
- `createMerchantAsOperator()` → POST `/v1/merchants` with the operator auth, read `$.merchantId` (check `CreateMerchantResponse`'s actual field name — mirror `MerchantsRestApiTest`/`FeeRestApiTest` which already exercise this route), return the id.
- The acting operator key's public id: mint via the service `operatorKeys.create("fee-probe", null)`, then read the id from `GET /v1/operator/api-keys` (`$.publicId`, newest first) or capture `$.keyId` from a REST mint.
- Test 1: PUT a new fee → 200; assert `entryCountForMerchant` incremented by exactly 1, the row's `created_by` equals the acting key's public id, and `GET /v1/merchants/{id}` shows the new rate (cache updated).
- Test 2: PUT the SAME values the merchant already carries → 200 and `entryCountForMerchant` still +1 (no-op appends).
- Test 3: two different PUTs → two entries, `id desc` ordering (the later PUT's rate appears first in the table's index order — assert via the listing route in Task 4; here assert counts and the cache equaling the SECOND rate).

- [ ] **Step 2: Remote RED** — push; FAIL (PUT appends nothing: `entryCountForMerchant` unchanged where +1 expected).

- [ ] **Step 3: Implement**

`MerchantsService`:

```java
  /** Appends an attributed history entry and updates the cached current
   *  schedule in one transaction. @param actingOperatorKey the calling
   *  operator key's public id (attribution). */
  boolean updateFeeSchedule(UUID merchantPublicId, FeeSchedule fee, UUID actingOperatorKey);
```

`MerchantsServiceImpl.updateFeeSchedule` becomes `@Transactional`: `store.insertFeeScheduleEntry(merchantPublicId, fee, actingOperatorKey)` then the existing `store.updateFeeSchedule(...)`; return the existing boolean. `MerchantsController.updateFee` passes `operator.keyPublicId()`.

`JdbcClientMerchantStore.insertFeeScheduleEntry`:

```java
  @Override
  public void insertFeeScheduleEntry(UUID merchantPublicId, FeeSchedule fee, UUID createdBy) {
    jdbc.sql("""
        insert into merchants.fee_schedule_entry (public_id, merchant_id, rate, fixed, created_by)
        select :entryId, m.id, :rate, :fixed, :createdBy
        from merchants.merchant m where m.public_id = :merchantPublicId
        """)
        .param("entryId", UUID.randomUUID())
        .param("rate", fee.rate())
        .param("fixed", fee.fixedAmount())
        .param("createdBy", createdBy)
        .param("merchantPublicId", merchantPublicId)
        .update();
  }
```

`MerchantStore` gains the method with javadoc (`created_by` nullable only for the migration backfill; the service always passes a real key).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='FeeHistoryRestApiTest,FeeRestApiTest,FeeSettlementTest'` → all green (existing fee suites untouched semantics); full `verify` → BUILD SUCCESS, **339 tests** (336 + 3).

- [ ] **Step 5: Commit** — `feat: attribute fee changes to the acting operator` + trailer; push.

---

### Task 4: The fee-history listing (TDD)

**Files:**
- Modify: `MerchantsController` (or a sibling controller — follow house layout) — `GET /v1/merchants/{id}/fee-history`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/FeeHistoryResponse.java`
- Modify: `MerchantStore` (+impl: `listFeeHistory(UUID merchantPublicId, UUID after, int limit)` keyset walk)
- Test: extends `FeeHistoryRestApiTest`

**Interfaces:**
- Produces: `GET /v1/merchants/{id}/fee-history?after=&limit=` — operator-authenticated, newest-first array of `{entryId, rate, fixed, validFrom, createdBy, createdByLabel}`, `Next-Cursor` header when more pages exist, `limit` 1–100 (outside → 400), unknown cursor → empty page without header, foreign/unknown merchant → 404. `createdByLabel` renders `"system"` when `created_by` is NULL.

- [ ] **Step 1: Write the failing tests** (append to `FeeHistoryRestApiTest`; reuse its helpers)

```java
  @Test
  void feeHistoryPaginatesWithNextCursor() throws Exception {
    String merchantId = createMerchantAsOperator(); // helper per Task 3's note
    putFee(merchantId, "0.0100", "0.10");
    putFee(merchantId, "0.0200", "0.20");
    putFee(merchantId, "0.0300", "0.30");
    MvcResult page1 = mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth()).param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(header().exists("Next-Cursor"))
        .andExpect(jsonPath("$[0].rate").value(0.0300))
        .andReturn();
    String cursor = page1.getResponse().getHeader("Next-Cursor");
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth()).param("limit", "2").param("after", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(header().doesNotExist("Next-Cursor"))
        .andExpect(jsonPath("$[0].rate").value(0.0100));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    String merchantId = createMerchantAsOperator();
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth())
            .param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void feeHistoryBoundsAndScoping() throws Exception {
    String merchantId = createMerchantAsOperator();
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth()).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth()).param("limit", "101"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID() + "/fee-history")
            .header("Authorization", operatorAuth()))
        .andExpect(status().isNotFound());
    // Merchant keys never reach the history surface.
    String merchantKey = ApiDrivers.createMerchantAndGetKey(mockMvc, operatorKeys, "Gated");
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void seedAttributionRendersAsSystem() throws Exception {
    String merchantId = createMerchantAsOperator();
    // The listing's LAST page bottom is the backfill seed: createdBy null,
    // createdByLabel 'system'.
    mockMvc.perform(get("/v1/merchants/" + merchantId + "/fee-history")
            .header("Authorization", operatorAuth()).param("limit", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].createdByLabel").value("system"))
        .andExpect(jsonPath("$[0].createdBy").value(org.hamcrest.Matchers.nullValue()));
  }
```

(`operatorAuth()` and `putFee(...)` are the class's local helpers per Task 3's note — `putFee` is a two-line PUT with the operator auth returning the MvcResult; the seed test relies on a fresh merchant's FIRST entry being the V16 backfill… NO — a merchant created AFTER V16 has no seed; its first entry is its first PUT. For the seed assertion, use the PRE-EXISTING seed merchant (`11111111-1111-4111-8111-111111111111`) whose only entry is the backfill: `GET /v1/merchants/11111111-1111-4111-8111-111111111111/fee-history` → one row, `createdByLabel` `system`.)

- [ ] **Step 2: Remote RED** — push; FAIL (404 on the route).

- [ ] **Step 3: Implement**

Store (keyset, mirrors the deliveries walk):

```java
  @Override
  public List<FeeHistoryEntry> listFeeHistory(UUID merchantPublicId, UUID after, int limit) {
    return jdbc.sql("""
        select e.public_id, e.rate, e.fixed, e.valid_from, e.created_by, k.label as created_by_label
        from merchants.fee_schedule_entry e
        join merchants.merchant m on m.id = e.merchant_id
        left join merchants.operator_key k on k.public_id = e.created_by
        where m.public_id = :merchantPublicId
          and (:after is null or e.id < (select f.id from merchants.fee_schedule_entry f
                where f.public_id = :after))
        order by e.id desc
        limit :limit
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> new FeeHistoryEntry(rs.getObject("public_id", UUID.class),
            rs.getBigDecimal("rate"), rs.getBigDecimal("fixed"),
            rs.getObject("valid_from", java.time.OffsetDateTime.class).toInstant(),
            rs.getObject("created_by", UUID.class), rs.getString("created_by_label")))
        .list();
  }
```

(`FeeHistoryEntry` is a small application record `(UUID entryId, BigDecimal rate, BigDecimal fixed, Instant validFrom, UUID createdBy, String createdByLabel)`; the NULL-safe label join means `createdByLabel` is null exactly when `createdBy` is — the response maps null → `"system"`.)

Controller (`MerchantsController`, following the deliveries-controller pagination idiom — 404 via `merchants.find(id)` or the first history row's absence; simplest house-consistent: `UnknownMerchantException` when `merchants.find(id)` is empty):

```java
  @GetMapping("/{id}/fee-history")
  ResponseEntity<List<FeeHistoryResponse>> feeHistory(AuthenticatedOperator operator,
      @PathVariable UUID id, @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    var page = store.listFeeHistory(id, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(FeeHistoryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).entryId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(FeeHistoryResponse::from).toList());
  }
```

(`MerchantsController` currently injects `MerchantsService` + `ApiKeysService`; it gains `MerchantStore` OR the walk moves behind `MerchantsService` — follow whichever matches the house seam (the deliveries controller injects the store directly; mirror that). `FeeHistoryResponse.from` maps null `createdByLabel` to `"system"` and null `createdBy` stays null.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='FeeHistoryRestApiTest,FeeRestApiTest,MerchantsRestApiTest'` → all green; full `verify` → BUILD SUCCESS, **343 tests** (339 + 4).

- [ ] **Step 5: Commit** — `feat: list attributed fee history with cursor pagination` + trailer; push.

---

### Task 5: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M14 — Audit attribution`; the **Authentication** capability row extends: "…; operator keys carry immutable identity labels and fee-schedule changes are attributed, append-only history".
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M14 design

M14 closed the M8/M9 attribution threads: operator keys carry immutable
identity labels (rotations carry the label forward; pre-M14 keys read
`system`), and every fee-schedule change appends an attributed,
append-only history entry while the merchant's columns stay the cached
current value. Known bounds, deliberate:

- **Only fee changes are attributed** — merchant creation, key operations,
  and conciliation triggers stay unattributed; a general audit log can
  build on the label seam.
- **No RBAC and no operator entity** — labels are identity-light; grouping
  and roles can come later without history rewrites.
- **PUT retries append per delivery** — a network-level retry of the fee
  route leaves two identical entries; honest as "applied twice".
- **`system` is a sentinel, not an actor** — pre-M14 attribution is
  genuinely absent (NULL rendered as `system`).
- **Labels are shape-validated only** — non-blank, ≤64 chars, no
  uniqueness or i18n constraints; the key id disambiguates.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 343, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M14 audit attribution complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V16 label + fee_schedule_entry + backfill (+ schema/roles tests) | 1 |
| Labels on mint/rotate/bootstrap/listings (+ validation, DTOs, ripples) | 2 |
| Attributed append + cache on PUT (transactional pair) | 3 |
| History listing: keyset walk, Next-Cursor, bounds, system rendering, operator gating | 4 |
| README + backlog + final gate (343) | 5 |
