# M8 Operator Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merchant creation and conciliation require an operator API key (own identity domain, same key mechanics); the first key bootstraps one-time from a deployment token; role mismatches are 403 in both directions; the simulator stays open.

**Architecture:** V11 adds `merchants.operator_key`. The auth filter resolves a Bearer against operator keys first, then merchant keys, setting `auth.operator` or `auth.merchant`; an `OPERATOR_ROUTES` prefix list (with an explicit bootstrap exemption) keeps 401-before-400 for headerless operator POSTs. Two sibling argument resolvers map attributes to `AuthenticatedOperator`/`AuthenticatedMerchant` parameters and turn role mismatches into 403. Idempotency needs zero change: operator requests carry no merchant attribute, so reservations keep the NULL namespace.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL via Testcontainers, JUnit 5 + MockMvc. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-19-m8-operator-auth-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits.
- **NO LOCAL MAVEN/JVM RUNS — ever.** Per run: `git push origin HEAD:refs/heads/worktree-m8-operator-auth` then substitute `<GOALS>`:
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m8-operator-auth origin/worktree-m8-operator-auth && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B <GOALS>'
  ```
  TDD: test-only RED commit → push → remote RED → implement → push → GREEN → remote `verify` → final commit → push. Quote `Tests run:`/`BUILD` lines as evidence.
- **Test classes end in `Test`.** Final count **208 tests** (198 today + 10 new: 2 schema/roles, 2 operator keys service, 4 auth/controllers, 2 gating E2E).
- **Context-green ordering:** Task 3 lands the filter changes, both resolvers, both operator controllers, and the advice entries in ONE commit; Task 4 gates the two controllers and sweeps the operator-route test suites in ONE commit.
- **Jackson 3** where serialization appears; **ArchUnit** JDBC-under-infrastructure unchanged.
- Key facts: secret format `nummus_sk_<43 base64url>`; SHA-256 hex of the whole key; prefix = first 12 chars; `MerchantsServiceImpl.sha256Hex` is the existing hashing helper (package-private — reuse via the same package).

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V11__operator_keys_schema.sql          (Task 1)
src/main/java/com/leandrossb/nummus/merchants/
  application/OperatorKeysService.java, OperatorKeysServiceImpl.java   (Task 2)
  application/MerchantStore.java (+5 operator methods)                 (Task 2)
  infrastructure/JdbcClientMerchantStore.java (+operator_key SQL)      (Task 2)
  interfaces/OperatorKeysController.java, OperatorBootstrapController.java (Task 3)
src/main/java/com/leandrossb/nummus/interfaces/auth/
  AuthenticatedOperator.java, OperatorAuthenticationPort.java,
  OperatorArgumentResolver.java, OperatorKeyRequiredException.java,
  MerchantKeyRequiredException.java, OperatorUnauthorizedException.java (Task 3)
src/main/java/com/leandrossb/nummus/merchants/application/OperatorAuthentication.java (Task 3)
  MerchantAuthFilter.java (two-stage + OPERATOR_ROUTES + exemption)    (Task 3)
  MerchantArgumentResolver.java (mirror 403)                           (Task 3)
  AuthWebConfig.java (+operator resolver)                               (Task 3)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (403 entries) (Task 3)
src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java (gating) (Task 4)
src/main/java/com/leandrossb/nummus/conciliation/interfaces/ConciliationReportsController.java (gating) (Task 4)
src/test/java/com/leandrossb/nummus/merchants/
  OperatorKeysSchemaTest.java, OperatorKeysRolesTest.java               (Task 1)
  OperatorKeysServiceTest.java                                          (Task 2)
  OperatorAuthRestApiTest.java                                          (Task 3)
  OperatorGatingTest.java                                               (Task 4)
README.md, docs/m2-backlog.md                                            (Task 5)
Sweep (Task 4): MerchantsRestApiTest, MerchantAuthFilterTest, MerchantScopingTest,
  IdempotencyMerchantNamespaceTest, ConciliationRestApiTest, IdempotencyRestApiTest
```

---

### Task 1: `V11__operator_keys_schema.sql` + schema and roles tests

**Files:**
- Create: `src/main/resources/db/migration/V11__operator_keys_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorKeysSchemaTest.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorKeysRolesTest.java`

**Interfaces:**
- Produces: table `merchants.operator_key` (identity pk, `public_id uuid unique default gen_random_uuid()`, `key_hash text not null unique`, `prefix text not null`, `status text check (ACTIVE|REVOKED) default 'ACTIVE'`, `created_at timestamptz not null default now()`); grants `select, insert, update` to `nummus_app`.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class OperatorKeysSchemaTest extends IntegrationTestBase {

  @Test
  void operatorKeyHashIsGloballyUniqueAndStatusChecked() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO merchants.operator_key (key_hash, prefix) VALUES ('hash-op-1', 'nummus_s')");
      SQLException duplicate = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO merchants.operator_key (key_hash, prefix) VALUES ('hash-op-1', 'nummus_s')"));
      assertEquals("23505", duplicate.getSQLState());
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO merchants.operator_key (key_hash, prefix, status) VALUES ('hash-op-2', 'nummus_s', 'GONE')"));
      assertEquals("23514", badStatus.getSQLState());
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=OperatorKeysSchemaTest` → FAIL (relation does not exist).

- [ ] **Step 3: Write the migration**

```sql
-- M8 operator authentication: the operator identity domain. Same key
-- mechanics as merchants.api_key (nummus_sk secret at issuance, SHA-256
-- hash at rest, display prefix), separate table — one key is one or the
-- other by which table holds its hash.

create table merchants.operator_key (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  key_hash    text not null unique,
  prefix      text not null,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at  timestamptz not null default now()
);

grant select, insert, update on merchants.operator_key to nummus_app;
```

- [ ] **Step 4: Write the roles test** (reuse the enable-login dance; positive insert + revocation UPDATE as `nummus_app`; assert readback `REVOKED`). Mirror `MerchantsRolesTest`'s structure with a unique `key_hash` per run.

- [ ] **Step 5: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, 200 tests.

- [ ] **Step 6: Commit** — `feat: add the operator key schema (V11)` + attribution trailer; push.

---

### Task 2: `OperatorKeysService` + store methods (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysService.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/OperatorKeysServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorKeysServiceTest.java`

**Interfaces:**
- Produces (Task 3–4 consume):
  - `MerchantStore` gains: `void insertOperatorKey(String keyHash, String prefix); Optional<ApiKey> findActiveOperatorKeyByHash(String keyHash); List<ApiKey> listOperatorKeys(); boolean revokeOperatorKey(UUID keyPublicId); boolean hasActiveOperatorKey();` (reuses the `ApiKey` record).
  - `interface OperatorKeysService { IssuedApiKey create(); List<ApiKey> list(); void revoke(UUID keyPublicId); Optional<ApiKey> findByRawKey(String rawKey); IssuedApiKey bootstrap(String presentedToken); }`
  - `bootstrap` semantics: if the configured token (`OperatorKeysService` reads `OperatorBootstrapProperties.token()`, may be null) is absent → `BootstrapUnavailableException` (new, `merchants.application`, → 404 via handler); if `hasActiveOperatorKey()` → `BootstrapAlreadyUsedException` (new, → 410); if the presented token does not constant-time-match the configured one → `InvalidBootstrapTokenException` (new, → 401); otherwise mint and return. Constant-time compare: `java.security.MessageDigest.isEqual(configured.getBytes(UTF_8), presented.getBytes(UTF_8))` (null presented → throw invalid).
  - Secret/hash/prefix mechanics identical to `ApiKeysServiceImpl` (reuse `MerchantsServiceImpl.sha256Hex`; the minting code may extract a shared private helper — keep it in this class if simpler, no refactor of M7 code beyond reuse).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.BootstrapAlreadyUsedException;
import com.leandrossb.nummus.merchants.application.BootstrapUnavailableException;
import com.leandrossb.nummus.merchants.application.InvalidBootstrapTokenException;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "nummus.operator.bootstrap-token=test-bootstrap-token")
class OperatorKeysServiceTest extends IntegrationTestBase {

  @Autowired
  private OperatorKeysService operatorKeys;

  @Test
  void bootstrapMintsOnceThenLocksOut() {
    var first = operatorKeys.bootstrap("test-bootstrap-token");
    assertTrue(first.secret().startsWith("nummus_sk_"));
    assertTrue(operatorKeys.findByRawKey(first.secret()).isPresent());

    assertThrows(BootstrapAlreadyUsedException.class,
        () -> operatorKeys.bootstrap("test-bootstrap-token"));
    assertThrows(InvalidBootstrapTokenException.class,
        () -> operatorKeys.bootstrap("wrong-token-with-same-length!!"));

    // Self-serve lifecycle still works after bootstrap is consumed.
    var minted = operatorKeys.create();
    assertNotEquals(first.secret(), minted.secret());
    operatorKeys.revoke(minted.key().publicId());
    assertTrue(operatorKeys.findByRawKey(minted.secret()).isEmpty());
    assertEquals(2, operatorKeys.list().size());
    assertThrows(com.leandrossb.nummus.merchants.application.UnknownApiKeyException.class,
        () -> operatorKeys.revoke(UUID.randomUUID()));
  }

  @Test
  void revokingEveryKeyReopensBootstrap() {
    var key = operatorKeys.create();
    operatorKeys.revoke(key.key().publicId());
    var again = operatorKeys.bootstrap("test-bootstrap-token");
    assertTrue(again.secret().startsWith("nummus_sk_"));
  }
}
```
NOTE on test isolation: `bootstrapMintsOnceThenLocksOut` and `revokingEveryKeyReopensBootstrap` share the operator-key table on the shared container, and JUnit method order is unspecified. Make them order-independent: in `revokingEveryKeyReopensBootstrap`, first REVOKE every active key via the service (`operatorKeys.list()` → revoke each ACTIVE), then bootstrap (succeeds because none active); in `bootstrapMintsOnceThenLocksOut`, make the `BootstrapAlreadyUsedException` assertion conditional-safe by revoking all keys FIRST, then bootstrapping once, then asserting the second throws. Both methods start from "no active keys" — deterministic under any order. Write them that way.

- [ ] **Step 2: Remote RED** — push; compilation FAIL (types absent).

- [ ] **Step 3: Implement** per Interfaces. `OperatorKeysServiceImpl` is `@Service`, constructor-injects `MerchantStore` and `OperatorBootstrapProperties` (a `@ConfigurationProperties(prefix = "nummus.operator")` record `OperatorBootstrapProperties(String token)` — create it in `merchants.application`; nullable token binds when unset). All methods `@Transactional` where they write.

- [ ] **Step 4: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, 202 tests.

- [ ] **Step 5: Commit** — `feat: add the operator key lifecycle and bootstrap` + trailer; push.

---

### Task 3: `interfaces.auth` two-stage resolution + operator controllers (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/AuthenticatedOperator.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/OperatorAuthenticationPort.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/OperatorArgumentResolver.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/OperatorKeyRequiredException.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantKeyRequiredException.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/OperatorUnauthorizedException.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/OperatorAuthentication.java` (implements the port via `OperatorKeysService`)
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorKeysController.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/OperatorBootstrapController.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthFilter.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantArgumentResolver.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/auth/AuthWebConfig.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorAuthRestApiTest.java`

**Interfaces:**
- Produces: `record AuthenticatedOperator(UUID keyPublicId)`; `interface OperatorAuthenticationPort { Optional<AuthenticatedOperator> authenticate(String rawBearerCredential); }`; filter attribute `MerchantAuthFilter.OPERATOR_ATTRIBUTE = "auth.operator"`; `OPERATOR_ROUTES` = `/v1/merchants`, `/v1/conciliation`, `/v1/operator` with the single exemption `/v1/operator/bootstrap` (exact match, checked before the prefix logic); 403 exceptions carry messages "An operator API key is required" / "A merchant API key is required".
- Filter resolution order: operator hash first, then merchant hash; ANY failed Bearer → 401 (unchanged); headerless on merchant OR operator route → 401; `authenticated` no longer takes only the merchant port — the filter takes BOTH ports.
- Resolvers: `MerchantArgumentResolver` throws `MerchantKeyRequiredException` (403) when the operator attribute is present but the merchant attribute is not, `MerchantUnauthorizedException` (401) when neither; `OperatorArgumentResolver` mirrors (`OperatorKeyRequiredException` 403 when merchant attribute present, `OperatorUnauthorizedException` 401 when neither).
- Advice: 403 group entry for both `*KeyRequiredException`s (message detail); `OperatorUnauthorizedException` joins the existing 401 entry's group; the bootstrap exceptions map 404/410/401 (three new entries: `BootstrapUnavailableException` → 404, `BootstrapAlreadyUsedException` → 410, `InvalidBootstrapTokenException` → 401 — all small `@ExceptionHandler` methods next to the existing ones).
- REST: `POST /v1/operator/bootstrap` body `{"token": "…"}` → 201 `CreateKeyResponse`; `POST /v1/operator/api-keys` (`@Idempotent`, `AuthenticatedOperator`) → 201 `CreateKeyResponse`; `GET /v1/operator/api-keys` → `List<ApiKeyResponse>`; `DELETE /v1/operator/api-keys/{id}` → 204 (double-revoke 404 via existing UnknownApiKey mapping).

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class OperatorAuthRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private com.leandrossb.nummus.merchants.application.OperatorKeysService operatorKeys;

  private String operatorKey() {
    return operatorKeys.create().secret();
  }

```java
@TestPropertySource(properties = "nummus.operator.bootstrap-token=rest-bootstrap-token")
// (annotation on the class)
```
and the test:
```java
  @Test
  void bootstrapMintsOnceOverHttp() throws Exception {
    operatorKeys.list().forEach(key -> operatorKeys.revoke(key.publicId()));
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"rest-bootstrap-token\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty());
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"rest-bootstrap-token\"}"))
        .andExpect(status().isGone());
    mockMvc.perform(post("/v1/operator/bootstrap")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"wrong-length-token-123\"}"))
        .andExpect(status().isGone()); // consumed beats invalid — hasActiveKey is checked first
  }
```
Plus five more tests (all minting keys via the autowired service — no bootstrap dependency):
```java
  @Test
  void selfServeKeyLifecycle() throws Exception {
    String auth = "Bearer " + operatorKey();
    var minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty()).andReturn();
    String keyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].prefix").exists())
        .andExpect(jsonPath("$[0].secret").doesNotExist());
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/v1/operator/api-keys/" + keyId).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/operator/api-keys").header("Authorization", auth))
        .andExpect(status().isOk());
  }

  @Test
  void operatorRoutesRejectKeylessAndMerchantKeys() throws Exception {
    String merchantKey = createMerchantKey(); // helper: POST /v1/merchants AS OPERATOR, read $.apiKey.secret
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/v1/conciliation/reports")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"from\":\"2026-09-19T10:00:00Z\",\"to\":\"2026-09-19T11:00:00Z\"}"))
        .andExpect(status().isUnauthorized()); // 401 before the idempotency 400
  }

  @Test
  void operatorKeyOnMerchantRouteIs403() throws Exception {
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + operatorKey()))
        .andExpect(status().isForbidden());
  }

  @Test
  void simulatorStaysOpen() throws Exception {
    mockMvc.perform(get("/simulator/charges/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  private String createMerchantKey() throws Exception {
    var created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + operatorKey())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Op Fixture Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }
```
NOTE: `operatorRoutesRejectKeylessAndMerchantKeys`'s conciliation 401 assertion and `createMerchantKey` DEPEND on Task 4's gating (merchants/conciliation controllers don't declare `AuthenticatedOperator` yet — those two assertions would fail at Task 3). Split the class pragmatically: keep in Task 3 only `bootstrapMintsOnceOverHttp`, `selfServeKeyLifecycle`, `operatorKeyOnMerchantRouteIs403` (resolver-driven, works pre-gating), and `simulatorStaysOpen`; move `operatorRoutesRejectKeylessAndMerchantKeys` + `createMerchantKey` into Task 4's `OperatorGatingTest`. Task 3 expected: +4 tests → 206.

- [ ] **Step 2: Remote RED** — push; FAIL (404s — no operator routes).

- [ ] **Step 3: Implement** per Interfaces. Filter excerpt (final shape):
```java
  public static final String OPERATOR_ATTRIBUTE = "auth.operator";
  private static final List<String> OPERATOR_ROUTES =
      List.of("/v1/merchants", "/v1/conciliation", "/v1/operator");
  private static final String BOOTSTRAP_PATH = "/v1/operator/bootstrap";

  // headerless: reject when merchant OR operator route (bootstrap exempt)
  // Bearer: operatorAuthentication.authenticate(raw) first → OPERATOR_ATTRIBUTE;
  //         empty → merchantAuthentication.authenticate(raw) → MERCHANT_ATTRIBUTE;
  //         both empty → reject(401)
```
(The filter constructor gains the `OperatorAuthenticationPort`.)
`OperatorAuthentication` (merchants.application):
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.auth.OperatorAuthenticationPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OperatorAuthentication implements OperatorAuthenticationPort {

  private final OperatorKeysService operatorKeys;

  public OperatorAuthentication(OperatorKeysService operatorKeys) {
    this.operatorKeys = operatorKeys;
  }

  @Override
  public Optional<AuthenticatedOperator> authenticate(String rawBearerCredential) {
    return operatorKeys.findByRawKey(rawBearerCredential)
        .map(key -> new AuthenticatedOperator(key.publicId()));
  }
}
```
Controllers per Interfaces (DTOs reused: `CreateKeyResponse`, `ApiKeyResponse`); bootstrap request record `BootstrapRequest(@NotBlank String token)` in `merchants/interfaces/dto/`.

- [ ] **Step 4: Remote GREEN + verify** — focused 4/4; full `verify` → BUILD SUCCESS, 206 tests.

- [ ] **Step 5: Commit** — `feat: authenticate operators with one-time-bootstrapped API keys` + trailer; push.

---

### Task 4: Gate merchants and conciliation + suite sweep (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java` (both methods gain `AuthenticatedOperator operator` first)
- Modify: `src/main/java/com/leandrossb/nummus/conciliation/interfaces/ConciliationReportsController.java` (all three methods)
- Test: `src/test/java/com/leandrossb/nummus/merchants/OperatorGatingTest.java` (the two tests moved out of Task 3: keyless 401 + merchant-key 403 on both surfaces, incl. conciliation 401-before-400; operator-authenticated merchant creation + conciliation ingest with idempotent replay in the NULL namespace)
- Sweep: `MerchantsRestApiTest`, `MerchantAuthFilterTest`, `MerchantScopingTest`, `IdempotencyMerchantNamespaceTest`, `ConciliationRestApiTest`, `IdempotencyRestApiTest` — every request to `/v1/merchants*` or `/v1/conciliation/*` gains `Authorization: Bearer <operatorKey>`; fixture: autowire `OperatorKeysService`, one key per class (`operatorKeys.create().secret()`), passed as a header. `MerchantAuthFilterTest`'s operator-route probe (`GET /v1/merchants/{uuid}` expecting 404) becomes 401 keyless / 200-with-operator-key semantics — update per the new contract.

- [ ] **Step 1: Failing tests** — `OperatorGatingTest` per the Task 3 note (2 tests).

- [ ] **Step 2: Remote RED** — push; FAIL (200s where 401/403 expected).

- [ ] **Step 3: Implement** the two controllers' gating + the sweep.

- [ ] **Step 4: Remote GREEN + verify** — full `verify` → BUILD SUCCESS, 208 tests (198 + Task 3's 4 + these 2 + Tasks 1–2's 4).

- [ ] **Step 5: Commit** — `feat: gate merchant creation and conciliation behind operator keys` + trailer; push.

---

### Task 5: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status gains `- [x] M8 — Operator authentication`; the Authentication capability row extends: "…; operators authenticate with separately-bootstrapped keys (merchant creation, conciliation)".
- Modify: `docs/m2-backlog.md` — append:

```markdown
## From the M8 review

M8 delivered operator authentication: one-time env bootstrap, operator-key
lifecycle (mint/list/revoke), merchant creation and conciliation gated
(401 keyless, 403 role-mismatch both directions), simulator open by design.
Known bounds, deliberate:

- **Operators are role-level, not person-level.** One key equals "an
  operator"; no per-operator identity, audit attribution, or RBAC.
- **Bootstrap lockout is operational.** Revoking every operator key
  re-arms the bootstrap — recovery requires redeploying with the env
  token set; a stolen token plus a full revoke is a takeover path (token
  handling is deployment security).
- **No key expiry or last_used_at**; constant-time compare covers the
  bootstrap token only — key hashes are exact-match indexed lookups.
- **Simulator stays unauthenticated** (non-production harness); a real
  deployment replaces the network boundary entirely.
```

- [ ] **Step 1: Remote full verify** — `Tests run: 208, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** README/backlog edits.
- [ ] **Step 4: Commit** — `docs: mark M8 operator authentication complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V11 schema + grants (+ roles) | 1 |
| Operator key lifecycle + one-time bootstrap (constant-time) | 2 |
| Two-stage resolution, OPERATOR_ROUTES + bootstrap exemption, sibling resolvers, 403 both ways, operator REST | 3 |
| Gating merchants + conciliation; NULL-namespace replay; suite sweep | 4 |
| Success criteria, README, backlog | 5 |
