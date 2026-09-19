# M7 Merchants and API Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merchants own their resources: operator-created merchants authenticate with Bearer API keys (SHA-256 at rest, secret shown once), and payment accounts, payment intents (via their account), webhook endpoints, and idempotency-key namespaces are scoped per merchant — cross-tenant access is 404.

**Architecture:** A new `merchants` module owns merchants + keys. The shared `interfaces.auth` layer (the idempotency pattern) holds the auth vocabulary: `MerchantAuthFilter` (Bearer → `AuthenticatedMerchant` request attribute, 401 before anything else) and a `HandlerMethodArgumentResolver` (a handler parameter of type `AuthenticatedMerchant` marks a merchant route). Controllers pass the merchant id into services; ownership is enforced in the services' SQL. Idempotency uniqueness becomes `(merchant, key)` via partial unique indexes; operator POSTs reserve with a NULL merchant.

**Tech Stack:** Java 25, Spring Boot 4.1.1, PostgreSQL via Testcontainers, JUnit 5 + MockMvc, ArchUnit. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-19-m7-merchants-auth-design.md`

## Global Constraints

- **English everywhere** — code, comments, commits, docs. Conventional Commits.
- **NO LOCAL MAVEN/JVM RUNS — ever.** Per run: `git push origin HEAD:refs/heads/worktree-m7-merchants` then substitute `<GOALS>`:
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m7-merchants origin/worktree-m7-merchants && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B <GOALS>'
  ```
  TDD: test-only RED commit → push → remote RED → implement → push → GREEN → remote `verify` → final commit → push. Quote `Tests run:`/`BUILD` lines as evidence.
- **Test classes end in `Test`.** Final count **196 tests** (181 today + 15 new: 2 schema/roles, 2 store, 4 merchants/auth REST, 3 scoping, 2 idempotency namespace, 2 ArchUnit).
- **Bean/context ordering:** the auth filter, the resolver, and the first merchant routes land in ONE task (Task 3) so every context stays green at every commit. Signature changes to shared services (Tasks 4–7) update ALL their callers — production and test — in the same commit.
- **Jackson 3** (`tools.jackson.databind.ObjectMapper`) for any serialization; annotations at `com.fasterxml.jackson.annotation.*`.
- **ArchUnit** `persistenceTypesOnlyInInfrastructure`: JDBC/`java.sql` only under `..infrastructure..`.
- **No cross-schema foreign keys** — cross-module references are `uuid` public ids (payments→accounts precedent).
- **Money/DB-clock discipline** unchanged (no money changes in M7).
- Seed merchant public id constant: `11111111-1111-4111-8111-111111111111`.

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V10__merchants_auth_schema.sql            (Task 1)
src/main/java/com/leandrossb/nummus/merchants/
  domain/Merchant.java, ApiKey.java                                       (Task 2)
  application/MerchantsService.java, MerchantsServiceImpl.java,
    ApiKeysService.java, ApiKeysServiceImpl.java,
    MerchantStore.java, IssuedApiKey.java, SeedMerchant.java,
    MerchantAuthentication.java                                           (Tasks 2–3)
  infrastructure/JdbcClientMerchantStore.java                             (Task 2)
  interfaces/MerchantsController.java, MeController.java,
    dto/CreateMerchantRequest.java, MerchantResponse.java,
    CreateMerchantResponse.java, ApiKeyResponse.java, CreateKeyResponse.java (Task 3)
src/main/java/com/leandrossb/nummus/interfaces/auth/
  AuthenticatedMerchant.java, MerchantAuthenticationPort.java,
  MerchantUnauthorizedException.java, MerchantAuthFilter.java,
  MerchantArgumentResolver.java, AuthWebConfig.java                      (Task 3)
src/main/java/com/leandrossb/nummus/interfaces/idempotency/
  IdempotencyWebFilter.java (@Order +1000)                                (Task 3)
  IdempotencyStore.java, IdempotencyAspect.java (merchant namespace)      (Task 7)
  infrastructure/JdbcClientIdempotencyStore.java                          (Task 7)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (Task 3: 401 entry)
src/main/java/com/leandrossb/nummus/accounts/**  (domain record + repo SQL + service sigs + controller) (Task 4)
src/main/java/com/leandrossb/nummus/payments/**  (service sigs + controller)                              (Task 5)
src/main/java/com/leandrossb/nummus/webhooks/**   (domain record + store SQL + service sigs + controllers) (Task 6)
src/test/java/com/leandrossb/nummus/merchants/
  MerchantsSchemaTest.java, MerchantsRolesTest.java                       (Task 1)
  MerchantStoreTest.java                                                 (Task 2)
  MerchantsRestApiTest.java, MerchantAuthFilterTest.java                  (Task 3)
  MerchantScopingTest.java                                               (Tasks 4–6 accumulate)
src/test/java/com/leandrossb/nummus/idempotency/IdempotencyMerchantNamespaceTest.java (Task 7)
src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java (Task 8)
README.md, docs/m2-backlog.md                                             (Task 9)
Existing REST/service tests updated per task (enumerated in each task)   (Tasks 3–7)
```

---

### Task 1: `V10__merchants_auth_schema.sql` + schema and roles tests

**Files:**
- Create: `src/main/resources/db/migration/V10__merchants_auth_schema.sql`
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantsSchemaTest.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantsRolesTest.java`

**Interfaces:**
- Consumes: Flyway chain (V1–V9), `IntegrationTestBase`.
- Produces: schema `merchants` (`merchant`: identity pk, `public_id uuid unique default gen_random_uuid()`, `name text not null`, `created_at` default now(); `api_key`: identity pk, `public_id uuid unique`, `merchant_id bigint not null references merchant(id)`, `key_hash text not null unique`, `prefix text not null`, `status text check (ACTIVE|REVOKED) default 'ACTIVE'`, `created_at`); grants `usage` + `select, insert, update` on both (update is for revocation). Seed merchant row (public id `11111111-1111-4111-8111-111111111111`). `accounts.payment_account` + `webhooks.webhook_endpoint` gain `merchant_public_id uuid not null` defaulting to the seed + indexes. `idempotency.idempotency_keys` gains nullable `merchant_public_id`, DROPS the inline `idempotency_keys_key_key` constraint, gains partial uniques: `idempotency_keys_merchant_key_uq (merchant_public_id, key) WHERE merchant_public_id IS NOT NULL` and `idempotency_keys_operator_key_uq (key) WHERE merchant_public_id IS NULL`.

- [ ] **Step 1: Write the failing schema test**

```java
package com.leandrossb.nummus.merchants;

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

class MerchantsSchemaTest extends IntegrationTestBase {

  @Test
  void seedMerchantBackfillsOwnershipAndIdempotencyNamespacesSplit() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery(
          "SELECT name FROM merchants.merchant WHERE public_id = '11111111-1111-4111-8111-111111111111'")) {
        assertTrue(rs.next());
        assertEquals("seed merchant", rs.getString(1));
      }
      // Ownership backfill: any pre-existing row carries the seed merchant.
      try (ResultSet rs = st.executeQuery(
          "SELECT count(*) FROM accounts.payment_account WHERE merchant_public_id IS NULL")) {
        rs.next();
        assertEquals(0, rs.getInt(1));
      }
      // Partial unique semantics: same key across two merchants is allowed;
      // within one merchant (or the operator namespace) it is not.
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'A')");
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'B')");
      st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, 'hash-x', 'nummus_s' FROM merchants.merchant WHERE name IN ('A','B')
          """);
      SQLException sameMerchantTwice = assertThrows(SQLException.class, () -> st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, 'hash-x', 'nummus_s' FROM merchants.merchant WHERE name = 'A'
          """));
      assertEquals("23505", sameMerchantTwice.getSQLState());

      String sharedKey = "idem-" + UUID.randomUUID();
      st.executeUpdate("INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
          + " VALUES ('" + sharedKey + "', decode('00','hex'), now() + interval '1 hour', '11111111-1111-4111-8111-111111111111')");
      st.executeUpdate("INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
          + " VALUES ('" + sharedKey + "', decode('00','hex'), now() + interval '1 hour', NULL)");
      SQLException secondOperatorRow = assertThrows(SQLException.class, () -> st.executeUpdate(
          "INSERT INTO idempotency.idempotency_keys (key, request_fingerprint, expires_at, merchant_public_id)"
              + " VALUES ('" + sharedKey + "', decode('01','hex'), now() + interval '1 hour', NULL)"));
      assertEquals("23505", secondOperatorRow.getSQLState());
    }
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=MerchantsSchemaTest` → FAIL (`schema "merchants" does not exist`).

- [ ] **Step 3: Write the migration**

```sql
-- M7 merchants: identity and API keys. Merchants own payment accounts,
-- webhook endpoints, and their idempotency-key namespace; existing rows
-- backfill to a seed merchant. api_key stores only the SHA-256 of the
-- secret (shown once at issuance) plus a display prefix.

create schema merchants;

create table merchants.merchant (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  name        text not null,
  created_at  timestamptz not null default now()
);

create table merchants.api_key (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  key_hash    text not null unique,
  prefix      text not null,
  status      text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at  timestamptz not null default now()
);

grant usage on schema merchants to nummus_app;
grant select, insert, update on merchants.merchant, merchants.api_key to nummus_app;

insert into merchants.merchant (public_id, name)
values ('11111111-1111-4111-8111-111111111111', 'seed merchant');

alter table accounts.payment_account
  add column merchant_public_id uuid not null
  default '11111111-1111-4111-8111-111111111111';
create index payment_account_merchant_idx on accounts.payment_account (merchant_public_id);

alter table webhooks.webhook_endpoint
  add column merchant_public_id uuid not null
  default '11111111-1111-4111-8111-111111111111';
create index webhook_endpoint_merchant_idx on webhooks.webhook_endpoint (merchant_public_id);

alter table idempotency.idempotency_keys
  add column merchant_public_id uuid;
-- V7 declared `key text not null unique` (inline constraint); uniqueness
-- moves to per-namespace partial unique indexes.
alter table idempotency.idempotency_keys
  drop constraint idempotency_keys_key_key;
create unique index idempotency_keys_merchant_key_uq
  on idempotency.idempotency_keys (merchant_public_id, key)
  where merchant_public_id is not null;
create unique index idempotency_keys_operator_key_uq
  on idempotency.idempotency_keys (key)
  where merchant_public_id is null;
```

- [ ] **Step 4: Write the roles test** (positive lifecycle incl. key revocation UPDATE as `nummus_app`)

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class MerchantsRolesTest extends IntegrationTestBase {

  @BeforeAll
  void enableAppRoleLogin() throws SQLException {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("ALTER ROLE nummus_app LOGIN PASSWORD '" + APP_ROLE_PASSWORD + "'");
    }
  }

  @Test
  void appRoleRunsTheMerchantAndKeyLifecycle() throws Exception {
    String keyHash = "hash-" + UUID.randomUUID();
    try (Connection c = appConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("INSERT INTO merchants.merchant (public_id, name) VALUES ('"
          + UUID.randomUUID() + "', 'Roles Merchant')");
      st.executeUpdate("""
          INSERT INTO merchants.api_key (merchant_id, key_hash, prefix)
          SELECT id, '%s', 'nummus_s' FROM merchants.merchant WHERE name = 'Roles Merchant'
          """.formatted(keyHash));
      st.executeUpdate("""
          UPDATE merchants.api_key SET status = 'REVOKED' WHERE key_hash = '%s'
          """.formatted(keyHash));
      try (ResultSet rs = st.executeQuery(
          "SELECT status FROM merchants.api_key WHERE key_hash = '" + keyHash + "'")) {
        rs.next();
        assertEquals("REVOKED", rs.getString(1));
      }
    }
  }
}
```

- [ ] **Step 5: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, 183 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V10__merchants_auth_schema.sql \
  src/test/java/com/leandrossb/nummus/merchants/
git commit -m "feat: add the merchants schema with ownership backfills (V10)

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 2: `merchants` domain, store, and services (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/domain/Merchant.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/domain/ApiKey.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/IssuedApiKey.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/SeedMerchant.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsServiceImpl.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysService.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/ApiKeysServiceImpl.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantStoreTest.java`

**Interfaces:**
- Produces (Tasks 3–7 consume):
  - `record Merchant(UUID publicId, String name, Instant createdAt)`
  - `record ApiKey(UUID publicId, String prefix, String status, Instant createdAt)`
  - `record IssuedApiKey(ApiKey key, String secret)` — secret present ONLY at issuance
  - `final class SeedMerchant { public static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111"); private SeedMerchant() {} }`
  - `interface MerchantStore { Merchant insertMerchant(Merchant merchant); Optional<Merchant> findMerchant(UUID publicId); void insertApiKey(UUID merchantPublicId, String keyHash, String prefix); Optional<ApiKey> findActiveKeyByHash(String keyHash); List<ApiKey> listKeys(UUID merchantPublicId); boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId); Optional<Merchant> findMerchantByKeyHash(String keyHash); }`
  - `interface MerchantsService { Merchant create(String name); Optional<Merchant> findByApiKey(String rawKey); Optional<Merchant> find(UUID publicId); }` — `findByApiKey` hashes SHA-256 hex and resolves via the store; returns empty for unknown OR revoked.
  - `interface ApiKeysService { IssuedApiKey create(UUID merchantPublicId); List<ApiKey> list(UUID merchantPublicId); void revoke(UUID merchantPublicId, UUID keyPublicId); }` — `revoke` throws `UnknownApiKeyException` (new, `merchants.application`, message `api key not found: <id>`) when the key is absent or not the merchant's.
  - Secret format `nummus_sk_<43 base64url chars>` (32 SecureRandom bytes); SHA-256 hex of the WHOLE raw key (`nummus_sk_…`) is the stored hash; prefix = first 12 chars of the raw key.

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantStoreTest extends IntegrationTestBase {

  @Autowired
  private MerchantsService merchants;

  @Autowired
  private ApiKeysService keys;

  @Test
  void createIssuesAKeyThatResolvesAndIsNeverReissued() {
    var merchant = merchants.create("Store Merchant");
    var first = merchants.findByApiKey("nummus_sk_definitely-unknown");
    assertTrue(first.isEmpty());

    // create() itself does not issue keys in this test's service shape — use the
    // controller-level creation in Task 3 for the bundled first key. Here: mint one.
    IssuedApiKey issued = keys.create(merchant.publicId());
    assertTrue(issued.secret().startsWith("nummus_sk_"));
    assertEquals(43 + "nummus_sk_".length(), issued.secret().length());
    assertEquals(issued.key().prefix(), issued.secret().substring(0, 12));
    assertEquals("ACTIVE", issued.key().status());

    var resolved = merchants.findByApiKey(issued.secret());
    assertTrue(resolved.isPresent());
    assertEquals(merchant.publicId(), resolved.get().publicId());

    // Revoked keys stop resolving; other keys are unaffected.
    var second = keys.create(merchant.publicId());
    keys.revoke(merchant.publicId(), issued.key().publicId());
    assertTrue(merchants.findByApiKey(issued.secret()).isEmpty());
    assertTrue(merchants.findByApiKey(second.secret()).isPresent());
    assertEquals(2, keys.list(merchant.publicId()).size());

    // Cross-merchant revoke is a miss.
    var other = merchants.create("Other Merchant");
    assertThrows(com.leandrossb.nummus.merchants.application.UnknownApiKeyException.class,
        () -> keys.revoke(other.publicId(), second.key().publicId()));
    assertNotEquals(merchant.publicId(), other.publicId());
  }

  @Test
  void secretsAreUniqueAndHashesAreStoredNotSecrets() throws Exception {
    var merchant = merchants.create("Hash Merchant");
    var issued = keys.create(merchant.publicId());
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery(
            "SELECT key_hash FROM merchants.api_key WHERE public_id = '" + issued.key().publicId() + "'")) {
      rs.next();
      assertTrue(!rs.getString(1).contains("nummus_sk_"), "the secret itself must never be stored");
      assertTrue(rs.getString(1).matches("[0-9a-f]{64}"));
    }
    UUID.randomUUID(); // silence unused-import adjustments during transcription
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=MerchantStoreTest` → compilation FAIL.

- [ ] **Step 3: Implement**

`Merchant.java` / `ApiKey.java` / `IssuedApiKey.java` / `SeedMerchant.java` / `UnknownApiKeyException.java` (in application):
```java
package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** The owning identity of accounts, webhook endpoints, and idempotency namespaces. */
public record Merchant(UUID publicId, String name, Instant createdAt) {
}
```
```java
package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** An API key's metadata. The secret exists only at issuance; the prefix is display-safe. */
public record ApiKey(UUID publicId, String prefix, String status, Instant createdAt) {
}
```
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;

/** A freshly minted key: the ONLY time the secret is visible. */
public record IssuedApiKey(ApiKey key, String secret) {
}
```
```java
package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** The V10 seed merchant that owns pre-M7 rows. */
public final class SeedMerchant {

  public static final UUID PUBLIC_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private SeedMerchant() {
  }
}
```
```java
package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** Raised for a key that does not exist or is not the caller's. */
public class UnknownApiKeyException extends RuntimeException {

  public UnknownApiKeyException(UUID publicId) {
    super("api key not found: " + publicId);
  }
}
```

`MerchantStore.java`:
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for merchants and their API keys. */
public interface MerchantStore {

  Merchant insertMerchant(Merchant merchant);

  Optional<Merchant> findMerchant(UUID publicId);

  /** Stores hash + prefix; the secret never reaches the store. */
  void insertApiKey(UUID merchantPublicId, String keyHash, String prefix);

  /** The ACTIVE key metadata for a hash, if any. */
  Optional<ApiKey> findActiveKeyByHash(String keyHash);

  List<ApiKey> listKeys(UUID merchantPublicId);

  /** @return false when the key is absent or not the merchant's. */
  boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId);

  /** The merchant owning the ACTIVE key with this hash. */
  Optional<Merchant> findMerchantByKeyHash(String keyHash);
}
```

`MerchantsServiceImpl.java`:
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MerchantsServiceImpl implements MerchantsService {

  private final MerchantStore store;

  public MerchantsServiceImpl(MerchantStore store) {
    this.store = store;
  }

  @Override
  public Merchant create(String name) {
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    return store.insertMerchant(new Merchant(UUID.randomUUID(), name, Instant.now()));
  }

  @Override
  public Optional<Merchant> findByApiKey(String rawKey) {
    if (rawKey == null || !rawKey.startsWith("nummus_sk_")) {
      return Optional.empty();
    }
    return store.findMerchantByKeyHash(sha256Hex(rawKey));
  }

  @Override
  public Optional<Merchant> find(UUID publicId) {
    return store.findMerchant(publicId);
  }

  static String sha256Hex(String rawKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
```
(`MerchantsService.java` is the interface with the three methods above.)

`ApiKeysServiceImpl.java`:
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Key lifecycle. Secrets are 256-bit, base64url, prefixed, shown exactly once. */
@Service
public class ApiKeysServiceImpl implements ApiKeysService {

  private static final String PREFIX = "nummus_sk_";

  private final MerchantStore store;
  private final SecureRandom random = new SecureRandom();

  public ApiKeysServiceImpl(MerchantStore store) {
    this.store = store;
  }

  @Override
  public IssuedApiKey create(UUID merchantPublicId) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String rawKey = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    UUID keyId = UUID.randomUUID();
    store.insertApiKey(merchantPublicId, MerchantsServiceImpl.sha256Hex(rawKey), rawKey.substring(0, 12));
    return new IssuedApiKey(new ApiKey(keyId, rawKey.substring(0, 12), "ACTIVE", Instant.now()), rawKey);
  }

  @Override
  public List<ApiKey> list(UUID merchantPublicId) {
    return store.listKeys(merchantPublicId);
  }

  @Override
  public void revoke(UUID merchantPublicId, UUID keyPublicId) {
    if (!store.revokeApiKey(merchantPublicId, keyPublicId)) {
      throw new UnknownApiKeyException(keyPublicId);
    }
  }
}
```
(`ApiKeysService.java` is the interface with those three methods.)

`JdbcClientMerchantStore.java`:
```java
package com.leandrossb.nummus.merchants.infrastructure;

import com.leandrossb.nummus.merchants.application.MerchantStore;
import com.leandrossb.nummus.merchants.domain.ApiKey;
import com.leandrossb.nummus.merchants.domain.Merchant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientMerchantStore implements MerchantStore {

  private final JdbcClient jdbc;

  public JdbcClientMerchantStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Merchant insertMerchant(Merchant merchant) {
    jdbc.sql("""
        insert into merchants.merchant (public_id, name, created_at)
        values (:publicId, :name, :createdAt)
        """)
        .param("publicId", merchant.publicId())
        .param("name", merchant.name())
        .param("createdAt", toOffsetDateTime(merchant.createdAt()))
        .update();
    return merchant;
  }

  @Override
  public Optional<Merchant> findMerchant(UUID publicId) {
    return jdbc.sql("""
        select public_id, name, created_at from merchants.merchant where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapMerchant(rs)).optional();
  }

  @Override
  public void insertApiKey(UUID merchantPublicId, String keyHash, String prefix) {
    jdbc.sql("""
        insert into merchants.api_key (public_id, merchant_id, key_hash, prefix)
        select :keyId, m.id, :keyHash, :prefix
        from merchants.merchant m where m.public_id = :merchantPublicId
        """)
        .param("keyId", UUID.randomUUID())
        .param("keyHash", keyHash)
        .param("prefix", prefix)
        .param("merchantPublicId", merchantPublicId)
        .update();
  }

  @Override
  public Optional<ApiKey> findActiveKeyByHash(String keyHash) {
    return jdbc.sql("""
        select public_id, prefix, status, created_at from merchants.api_key
        where key_hash = :keyHash and status = 'ACTIVE'
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapKey(rs)).optional();
  }

  @Override
  public List<ApiKey> listKeys(UUID merchantPublicId) {
    return jdbc.sql("""
        select k.public_id, k.prefix, k.status, k.created_at
        from merchants.api_key k join merchants.merchant m on m.id = k.merchant_id
        where m.public_id = :merchantPublicId order by k.id desc
        """)
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> mapKey(rs)).list();
  }

  @Override
  public boolean revokeApiKey(UUID merchantPublicId, UUID keyPublicId) {
    return jdbc.sql("""
        update merchants.api_key k set status = 'REVOKED'
        from merchants.merchant m
        where k.merchant_id = m.id and m.public_id = :merchantPublicId
          and k.public_id = :keyPublicId and k.status = 'ACTIVE'
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("keyPublicId", keyPublicId)
        .update() == 1;
  }

  @Override
  public Optional<Merchant> findMerchantByKeyHash(String keyHash) {
    return jdbc.sql("""
        select m.public_id, m.name, m.created_at
        from merchants.merchant m join merchants.api_key k on k.merchant_id = m.id
        where k.key_hash = :keyHash and k.status = 'ACTIVE'
        """)
        .param("keyHash", keyHash)
        .query((rs, i) -> mapMerchant(rs)).optional();
  }

  private static Merchant mapMerchant(ResultSet rs) throws SQLException {
    return new Merchant(rs.getObject("public_id", UUID.class), rs.getString("name"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ApiKey mapKey(ResultSet rs) throws SQLException {
    return new ApiKey(rs.getObject("public_id", UUID.class), rs.getString("prefix"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(java.time.Instant instant) {
    return instant.atOffset(java.time.ZoneOffset.UTC);
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused 2/2; full `verify` → BUILD SUCCESS, 185 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/merchants/ \
  src/test/java/com/leandrossb/nummus/merchants/MerchantStoreTest.java
git commit -m "feat: add the merchants module core

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 3: `interfaces.auth` + merchants REST + first merchant routes (TDD)

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/AuthenticatedMerchant.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthenticationPort.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantUnauthorizedException.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthFilter.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantArgumentResolver.java`
- Create: `src/main/java/com/leandrossb/nummus/interfaces/auth/AuthWebConfig.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantAuthentication.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/MeController.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/CreateMerchantRequest.java`, `MerchantResponse.java`, `CreateMerchantResponse.java`, `ApiKeyResponse.java`, `CreateKeyResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyWebFilter.java` (`@Order(Ordered.HIGHEST_PRECEDENCE + 1000)`)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java` (401 entry)
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantsRestApiTest.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantAuthFilterTest.java`

**Interfaces:**
- Produces (Tasks 4–7 consume): `record AuthenticatedMerchant(UUID merchantPublicId, String name)`; `interface MerchantAuthenticationPort { Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential); }`; `MerchantUnauthorizedException` (message "A valid API key is required"); request attribute key `MerchantAuthFilter.MERCHANT_ATTRIBUTE` = `"auth.merchant"`. Controllers add a parameter `AuthenticatedMerchant merchant` — its presence makes the route merchant-facing.
- REST (operator, no auth): `POST /v1/merchants {name}` → 201 `{merchantId, name, createdAt, apiKey}` (secret once; `@Idempotent`), `GET /v1/merchants/{id}` → 404 unknown via `UnknownMerchantException` (new, merchants.application → the existing notFound group).
- REST (Bearer): `GET /v1/me` → `{merchantId, name, createdAt}`; `POST /v1/me/api-keys` (`@Idempotent`) → 201 `{keyId, prefix, status, createdAt, secret}` (once); `GET /v1/me/api-keys` → prefixes only; `DELETE /v1/me/api-keys/{id}` → 204.

- [ ] **Step 1: Write the failing tests**

`MerchantsRestApiTest.java`:
```java
package com.leandrossb.nummus.merchants;

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
class MerchantsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Test
  void createReturnsTheFirstKeyExactlyOnce() throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rest Merchant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.merchantId").exists())
        .andExpect(jsonPath("$.apiKey.secret").exists())
        .andExpect(jsonPath("$.apiKey.secret").isNotEmpty())
        .andReturn();
    String merchantId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");

    mockMvc.perform(get("/v1/merchants/" + merchantId)).andExpect(status().isOk())
        .andExpect(jsonPath("$.apiKey").doesNotExist());
    mockMvc.perform(get("/v1/merchants/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void selfServeKeyLifecycleOverMe() throws Exception {
    String firstKey = createMerchant("Me Merchant");
    String auth = "Bearer " + firstKey;

    mockMvc.perform(get("/v1/me").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Me Merchant"));

    MvcResult minted = mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", auth).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andReturn();
    String secondKey = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.secret");
    String secondKeyId = com.jayway.jsonpath.JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");

    mockMvc.perform(get("/v1/me/api-keys").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].prefix").exists())
        .andExpect(jsonPath("$[0].secret").doesNotExist());

    mockMvc.perform(delete("/v1/me/api-keys/" + secondKeyId).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer " + secondKey))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/v1/me/api-keys/" + secondKeyId).header("Authorization", auth))
        .andExpect(status().isNotFound());
  }

  private String createMerchant(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }
}
```

`MerchantAuthFilterTest.java`:
```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class MerchantAuthFilterTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void merchantRoutesRequireAValidBearerKey() throws Exception {
    mockMvc.perform(get("/v1/me")).andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer garbage"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/me").header("Authorization", "Bearer nummus_sk_unknown-key-unknown-key-unknown-key"))
        .andExpect(status().isUnauthorized());
    // Operator routes never require auth.
    mockMvc.perform(get("/v1/merchants/" + java.util.UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void authenticationPrecedesIdempotencyValidation() throws Exception {
    // Neither an API key nor an Idempotency-Key: 401 (auth), not 400 (idempotency).
    mockMvc.perform(post("/v1/me/api-keys")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest='MerchantsRestApiTest,MerchantAuthFilterTest'` → FAIL (404/405s; no routes).

- [ ] **Step 3: Implement**

`AuthenticatedMerchant.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

import java.util.UUID;

/** The authenticated caller, resolved from a Bearer API key. Shared vocabulary:
 *  controllers declare this parameter to mark a merchant route. */
public record AuthenticatedMerchant(UUID merchantPublicId, String name) {
}
```

`MerchantAuthenticationPort.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

import java.util.Optional;

/** Resolves a raw bearer credential to a merchant. Implemented by the merchants module. */
public interface MerchantAuthenticationPort {

  Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential);
}
```

`MerchantUnauthorizedException.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

/** Missing, invalid, or revoked credentials on a merchant route. */
public class MerchantUnauthorizedException extends RuntimeException {

  public MerchantUnauthorizedException() {
    super("A valid API key is required");
  }
}
```

`MerchantAuthFilter.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

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
 * Resolves Bearer credentials to a merchant before anything else runs (the
 * idempotency filter sits at HIGHEST_PRECEDENCE + 1000 — no auth means 401,
 * never a 400). Requests without an Authorization header pass through;
 * merchant routes reject them at the argument resolver. Renders 401 itself:
 * a filter runs outside the advice's reach.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MerchantAuthFilter extends OncePerRequestFilter {

  public static final String MERCHANT_ATTRIBUTE = "auth.merchant";
  private static final String BEARER_PREFIX = "Bearer ";

  private final MerchantAuthenticationPort authentication;
  private final ObjectMapper objectMapper;

  public MerchantAuthFilter(MerchantAuthenticationPort authentication, ObjectMapper objectMapper) {
    this.authentication = authentication;
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, jakarta.servlet.ServletException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith(BEARER_PREFIX)) {
      var merchant = authentication.authenticate(header.substring(BEARER_PREFIX.length()));
      if (merchant.isEmpty()) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
            new MerchantUnauthorizedException().getMessage());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
        return;
      }
      request.setAttribute(MERCHANT_ATTRIBUTE, merchant.get());
    }
    chain.doFilter(request, response);
  }
}
```
(Jackson 3: use `tools.jackson.databind.ObjectMapper` — write the import accordingly.)

`MerchantArgumentResolver.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** A handler parameter of type AuthenticatedMerchant marks a merchant route;
 *  reaching one without authenticated credentials is a 401 (via the advice). */
public class MerchantArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(AuthenticatedMerchant.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Object merchant = request == null ? null : request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE);
    if (merchant == null) {
      throw new MerchantUnauthorizedException();
    }
    return merchant;
  }
}
```

`AuthWebConfig.java`:
```java
package com.leandrossb.nummus.interfaces.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the merchant argument resolver. */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new MerchantArgumentResolver());
  }
}
```

`IdempotencyWebFilter` — change `@Order(Ordered.HIGHEST_PRECEDENCE)` to `@Order(Ordered.HIGHEST_PRECEDENCE + 1000)` and extend the class javadoc by one line: "Authentication (MerchantAuthFilter) runs first."

`GlobalExceptionHandler` — new entry (import `com.leandrossb.nummus.interfaces.auth.MerchantUnauthorizedException`, and `com.leandrossb.nummus.merchants.application.UnknownMerchantException` joins the notFound group):
```java
  @ExceptionHandler(MerchantUnauthorizedException.class)
  ProblemDetail merchantUnauthorized(MerchantUnauthorizedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }
```
Create `merchants/application/UnknownMerchantException.java` (message `merchant not found: <id>`).

`MerchantAuthentication.java` (merchants.application — implements the shared port):
```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.auth.MerchantAuthenticationPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MerchantAuthentication implements MerchantAuthenticationPort {

  private final MerchantsService merchants;

  public MerchantAuthentication(MerchantsService merchants) {
    this.merchants = merchants;
  }

  @Override
  public Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential) {
    return merchants.findByApiKey(rawBearerCredential)
        .map(merchant -> new AuthenticatedMerchant(merchant.publicId(), merchant.name()));
  }
}
```

DTOs (`merchants/interfaces/dto/`):
```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateMerchantRequest(@NotBlank(message = "name must not be blank") String name) {
}
```
```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.time.Instant;
import java.util.UUID;

/** Merchant view without key material. */
public record MerchantResponse(UUID merchantId, String name, Instant createdAt) {

  public static MerchantResponse from(Merchant merchant) {
    return new MerchantResponse(merchant.publicId(), merchant.name(), merchant.createdAt());
  }
}
```
```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.IssuedApiKey;

/** Create responses carry the secret exactly once. */
public record CreateKeyResponse(UUID keyId, String prefix, String status,
    java.time.Instant createdAt, String secret) {

  public static CreateKeyResponse from(IssuedApiKey issued) {
    return new CreateKeyResponse(issued.key().publicId(), issued.key().prefix(),
        issued.key().status(), issued.key().createdAt(), issued.secret());
  }
}
```
```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Instant;
import java.util.UUID;

/** Key listing view — prefix only, never the secret. */
public record ApiKeyResponse(UUID keyId, String prefix, String status, Instant createdAt) {

  public static ApiKeyResponse from(ApiKey key) {
    return new ApiKeyResponse(key.publicId(), key.prefix(), key.status(), key.createdAt());
  }
}
```
```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.time.Instant;
import java.util.UUID;

/** Merchant creation: the FIRST API key's secret appears here and nowhere else. */
public record CreateMerchantResponse(
    UUID merchantId, String name, Instant createdAt, CreateKeyResponse apiKey) {

  public static CreateMerchantResponse from(Merchant merchant, com.leandrossb.nummus.merchants.application.IssuedApiKey firstKey) {
    return new CreateMerchantResponse(merchant.publicId(), merchant.name(), merchant.createdAt(),
        CreateKeyResponse.from(firstKey));
  }
}
```

`MerchantsController.java`:
```java
package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.UnknownMerchantException;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateMerchantRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateMerchantResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.MerchantResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator surface: merchants are created here, never self-served. */
@RestController
@RequestMapping("/v1/merchants")
class MerchantsController {

  private final MerchantsService merchants;
  private final ApiKeysService keys;

  MerchantsController(MerchantsService merchants, ApiKeysService keys) {
    this.merchants = merchants;
    this.keys = keys;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<CreateMerchantResponse> create(@Valid @RequestBody CreateMerchantRequest request) {
    var merchant = merchants.create(request.name());
    var firstKey = keys.create(merchant.publicId());
    return ResponseEntity
        .created(URI.create("/v1/merchants/" + merchant.publicId()))
        .body(CreateMerchantResponse.from(merchant, firstKey));
  }

  @GetMapping("/{id}")
  MerchantResponse get(@PathVariable UUID id) {
    return MerchantResponse.from(merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id)));
  }
}
```

`MeController.java`:
```java
package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.ApiKeysService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.UnknownApiKeyException;
import com.leandrossb.nummus.merchants.interfaces.dto.ApiKeyResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.CreateKeyResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.MerchantResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-serve surface: the authenticated merchant manages its own keys. */
@RestController
class MeController {

  private final ApiKeysService keys;
  private final MerchantsService merchants;

  MeController(ApiKeysService keys, MerchantsService merchants) {
    this.keys = keys;
    this.merchants = merchants;
  }

  @GetMapping("/v1/me")
  MerchantResponse me(AuthenticatedMerchant merchant) {
    return merchants.find(merchant.merchantPublicId())
        .map(MerchantResponse::from)
        .orElseThrow(() -> new com.leandrossb.nummus.merchants.application.UnknownMerchantException(
            merchant.merchantPublicId()));
  }

  @Idempotent
  @PostMapping("/v1/me/api-keys")
  ResponseEntity<CreateKeyResponse> createKey(AuthenticatedMerchant merchant) {
    var issued = keys.create(merchant.merchantPublicId());
    return ResponseEntity
        .created(URI.create("/v1/me/api-keys/" + issued.key().publicId()))
        .body(CreateKeyResponse.from(issued));
  }

  @GetMapping("/v1/me/api-keys")
  List<ApiKeyResponse> listKeys(AuthenticatedMerchant merchant) {
    return keys.list(merchant.merchantPublicId()).stream().map(ApiKeyResponse::from).toList();
  }

  @DeleteMapping("/v1/me/api-keys/{id}")
  ResponseEntity<Void> revoke(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    keys.revoke(merchant.merchantPublicId(), id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 4: Remote GREEN + verify** — focused (4 tests) green; full `verify` → BUILD SUCCESS, 189 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ \
  src/test/java/com/leandrossb/nummus/merchants/
git commit -m "feat: authenticate merchants with Bearer API keys

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 4: Accounts scoping (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/accounts/domain/PaymentAccount.java` (+`merchantPublicId` first component)
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java` (all methods gain `UUID merchantPublicId` first parameter; `open` keeps `OpenAccountCommand`)
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java` + `AccountsRepository.java` + `infrastructure/JdbcClientAccountsRepository.java` (SQL ownership predicates: `where public_id = :publicId and merchant_public_id = :merchantPublicId`; insert carries `merchant_public_id`)
- Modify: `src/main/java/com/leandrossb/nummus/accounts/interfaces/AccountsController.java` (every method gains `AuthenticatedMerchant merchant` and passes `merchant.merchantPublicId()`; `AccountResponse.from` unchanged — the merchant is implicitly the caller)
- Test: `src/test/java/com/leandrossb/nummus/merchants/MerchantScopingTest.java` (new)
- Update: `AccountsRestApiTest` (merchant fixture + Authorization header on every request; `createAccount` helper returns the merchant's key too), and every OTHER caller of `AccountsService`/`PaymentAccount` in the same commit: `PaymentsServiceImpl` (accounts.get/open calls gain `SeedMerchant.PUBLIC_ID` for now — Task 5 replaces with the parameter), `PaymentsPublishRaceTest`, `PaymentSettlementConcurrencyTest`, `PaymentsSettlementQueryTest`, `ConciliationRestApiTest`, `WebhookPublishTest` (direct service calls get `SeedMerchant.PUBLIC_ID`).

**Interfaces:**
- Produces: `AccountsService.open(UUID merchant, OpenAccountCommand cmd)`, `get/freeze/unfreeze/close/balance/statement(UUID merchant, UUID publicId[, Page])`. Cross-tenant → `UnknownPaymentAccountException` (404, unchanged exception). `PaymentAccount(publicId → merchantPublicId` inserted as FIRST component: `(UUID merchantPublicId, UUID publicId, String holderName, AccountStatus status, Instant openedAt, Instant closedAt, UUID ledgerAccountPublicId)` — update every constructor site.

- [ ] **Step 1: Write the failing scoping test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MerchantScopingTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String openAccount(String bearer) throws Exception {
    MvcResult opened = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"Scoped Holder\"}"))
        .andExpect(status().isCreated()).andReturn();
    return opened.getResponse().getHeader("Location");
  }

  @Test
  void merchantARoutesCannotSeeMerchantBResources() throws Exception {
    String a = createMerchantAndGetKey("Merchant A");
    String b = createMerchantAndGetKey("Merchant B");
    String aLocation = openAccount(a);

    // The owner sees it; the other merchant gets 404 on every surface.
    mockMvc.perform(get(aLocation).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get(aLocation).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(aLocation + "/balance").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(aLocation + "/statement").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(post(aLocation + "/freeze")
            .header("Authorization", "Bearer " + b).header(KEY, UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
    // Unauthenticated access is 401, and authentication precedes idempotency.
    mockMvc.perform(get(aLocation)).andExpect(status().isUnauthorized());
    mockMvc.perform(post(aLocation + "/freeze")
            .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }
}
```

- [ ] **Step 2: Remote RED** — push; remote `test -Dtest=MerchantScopingTest` → FAIL (200s for B; 200 unauthenticated — scoping absent).

- [ ] **Step 3: Implement** (exact edits)

`PaymentAccount` — new first component `UUID merchantPublicId` (update javadoc: owned by a merchant). Every `new PaymentAccount(...)` site gains the first argument.
`AccountsService`:
```java
  PaymentAccount open(UUID merchantPublicId, OpenAccountCommand cmd);

  PaymentAccount get(UUID merchantPublicId, UUID publicId);

  PaymentAccount freeze(UUID merchantPublicId, UUID publicId);

  PaymentAccount unfreeze(UUID merchantPublicId, UUID publicId);

  PaymentAccount close(UUID merchantPublicId, UUID publicId);

  Money balance(UUID merchantPublicId, UUID publicId);

  AccountStatement statement(UUID merchantPublicId, UUID publicId, Page page);
```
`AccountsServiceImpl` — each method passes `merchantPublicId` into the repository; `open` constructs `new PaymentAccount(merchantPublicId, UUID.randomUUID(), cmd.holderName(), …)` and opens the ledger account as today.
`AccountsRepository` — `insert` unchanged (the record carries the merchant); `findByPublicId(UUID merchantPublicId, UUID publicId)`; `updateStatus(UUID merchantPublicId, UUID publicId, …)` — SQL:
```sql
        select public_id, merchant_public_id, holder_name, status, ledger_account_public_id, opened_at, closed_at
        from accounts.payment_account
        where public_id = :publicId and merchant_public_id = :merchantPublicId
```
```sql
        update accounts.payment_account set status = :status, closed_at = :closedAt
        where public_id = :publicId and merchant_public_id = :merchantPublicId
```
and the insert gains `merchant_public_id` column + param.
`AccountsController` — every method: parameter `AuthenticatedMerchant merchant` first, pass `merchant.merchantPublicId()`.
`PaymentsServiceImpl` — the `accounts.get(...)` / `accounts.open`-adjacent call sites get `SeedMerchant.PUBLIC_ID` as the first argument FOR NOW (Task 5 threads the real parameter); import `com.leandrossb.nummus.merchants.application.SeedMerchant`.
Test updates: every direct `accountsService.open(new OpenAccountCommand(...))` call in `PaymentsPublishRaceTest`, `PaymentSettlementConcurrencyTest`, `PaymentsSettlementQueryTest`, `ConciliationRestApiTest`, `WebhookPublishTest` becomes `accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand(...))` (and `accountsService.freeze/publicId` calls gain the first arg); every constructor of `PaymentAccount` in test fakes gains the merchant first.
`AccountsRestApiTest` — add a fixture: create a merchant via REST in a helper (like `MerchantScopingTest.createMerchantAndGetKey`), keep the raw key in a field, and add `.header("Authorization", "Bearer " + merchantKey)` to EVERY request in the class (including `createAccount`'s POST). Assertions unchanged.

- [ ] **Step 4: Remote GREEN + verify** — focused scoping 1/1 + updated suites; full `verify` → BUILD SUCCESS, 190 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/
git commit -m "feat: scope payment accounts to their merchant

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 5: Payments scoping (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java` + `Impl` — `create(UUID merchantPublicId, CreateIntentCommand cmd)` and `get(UUID merchantPublicId, UUID publicId)`; internal `settle(PaymentIntent intent, UUID merchantPublicId)`; the `accounts.get` calls use the parameter (drop the Task-4 `SeedMerchant` stopgap)
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java` (`AuthenticatedMerchant` on create/get)
- Modify: `src/test/java/com/leandrossb/nummus/merchants/MerchantScopingTest.java` (+ cross-tenant intent test)
- Update callers: `PaymentsPublishRaceTest` (direct construction passes a merchant), `PaymentSettlementConcurrencyTest`, `PaymentsSettlementQueryTest`, `ConciliationRestApiTest`, `WebhookPublishTest` (service calls gain `SeedMerchant.PUBLIC_ID`), `PaymentsRestApiTest` (Authorization header everywhere; account/intent fixtures under the merchant).

**Interfaces:**
- `PaymentsService.create(UUID merchant, CreateIntentCommand)` — the intent's account must be the merchant's (accounts internal API → 404 otherwise). `get(UUID merchant, UUID publicId)` — loads the intent, then verifies account ownership via `accounts.get(merchant, intent.accountPublicId())` → `UnknownPaymentAccountException` → 404. Ownership is checked BEFORE the charge poll and lazy transitions (never act on another merchant's intent).

- [ ] **Step 1: Failing test** (append to `MerchantScopingTest`)

```java
  @Test
  void intentsAreInvisibleAcrossMerchants() throws Exception {
    String a = createMerchantAndGetKey("Intent A");
    String b = createMerchantAndGetKey("Intent B");
    String accountLocation = openAccount(a);
    String accountId = accountLocation.substring(accountLocation.lastIndexOf('/') + 1);
    MvcResult intentCreated = mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + a)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isCreated()).andReturn();
    String intentLocation = intentCreated.getResponse().getHeader("Location");

    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get(intentLocation).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    // B cannot create an intent against A's account.
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + b)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":5.0000}"))
        .andExpect(status().isNotFound());
  }
```

- [ ] **Step 2: Remote RED** — push; FAIL (B sees A's intent: 200).

- [ ] **Step 3: Implement** per Interfaces (exact signature changes; `settle` gains the merchant and passes it to `accounts.get`; the fail/expire lazy branches keep publishing under the ownership established at method entry). Update all callers listed above.

- [ ] **Step 4: Remote GREEN + verify** — full `verify` → BUILD SUCCESS, 191 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/
git commit -m "feat: scope payment intents to their merchant

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 6: Webhook endpoints scoping (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/webhooks/domain/WebhookEndpoint.java` (+ first component `UUID merchantPublicId`)
- Modify: `webhooks/application/WebhookStore.java` + `infrastructure/JdbcClientWebhookStore.java` (`insertEndpoint` carries the merchant from the record; `listActiveEndpoints(UUID merchantPublicId)`; `findActiveEndpoint(UUID merchantPublicId, UUID publicId)`; `markEndpointDeleted(UUID merchantPublicId, UUID publicId)`; deliveries listing's endpoint join gains the merchant predicate)
- Modify: `webhooks/application/WebhookEndpointsService.java` (methods gain `UUID merchantPublicId`)
- Modify: `webhooks/interfaces/WebhookEndpointsController.java` + `WebhookDeliveriesController.java` (`AuthenticatedMerchant`)
- Modify: `src/test/java/com/leandrossb/nummus/merchants/MerchantScopingTest.java` (+ endpoint cross-tenant test)
- Update: `WebhookEndpointsRestApiTest` (merchant fixture + Authorization everywhere), `WebhookStoreTest`, `WebhookDeliveryWorkerTest`, `WebhookDeliveryClientTest` (endpoint construction gains merchant — use `SeedMerchant.PUBLIC_ID`), `WebhookPublishTest` (registerEndpoint uses seed), `ConciliationRestApiTest` (unchanged — no endpoints).

- [ ] **Step 1: Failing test** (append to `MerchantScopingTest`)

```java
  @Test
  void webhookEndpointsAreIsolatedPerMerchant() throws Exception {
    String a = createMerchantAndGetKey("Hook A");
    String b = createMerchantAndGetKey("Hook B");
    MvcResult endpoint = mockMvc.perform(post("/v1/webhook-endpoints")
            .header("Authorization", "Bearer " + a)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"https://a.example/hook\"}"))
        .andExpect(status().isCreated()).andReturn();
    String location = endpoint.getResponse().getHeader("Location");

    mockMvc.perform(get(location).header("Authorization", "Bearer " + a))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/webhook-endpoints").header("Authorization", "Bearer " + b))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
    mockMvc.perform(get(location).header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(get(location + "/deliveries").header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(location)
            .header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
  }
```

- [ ] **Step 2: Remote RED** — push; FAIL.

- [ ] **Step 3: Implement** per Interfaces (SQL ownership predicates mirror Task 4's; `EndpointResponse`/`CreateEndpointResponse` unchanged — merchant implicit).

- [ ] **Step 4: Remote GREEN + verify** — full `verify` → BUILD SUCCESS, 192 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/
git commit -m "feat: scope webhook endpoints to their merchant

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 7: Idempotency merchant namespace (TDD)

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyStore.java` (`insert(String merchantPublicId-as-UUID, String key, byte[] fingerprint, Instant expiresAt)` — null merchant = operator namespace; `findByKey(UUID merchantPublicId, String key)`)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/infrastructure/JdbcClientIdempotencyStore.java` (insert carries `merchant_public_id`; find uses `(merchant_public_id IS NOT DISTINCT FROM :merchant)` + `key = :key`; reclaim/purge unchanged predicates)
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/idempotency/IdempotencyAspect.java` (read the merchant from the request attribute — `AuthenticatedMerchant` from `MerchantAuthFilter.MERCHANT_ATTRIBUTE`, null when absent → operator namespace; pass merchant to insert/findByKey)
- Test: `src/test/java/com/leandrossb/nummus/idempotency/IdempotencyMerchantNamespaceTest.java`
- Update: `IdempotencyStoreTest` (insert/findByKey signatures), `IdempotencyRestApiTest`, `IdempotencyExpiryTest`, `IdempotencyConcurrencyTest` (Authorization fixtures — merchant for accounts/payments paths; note `/v1/me/api-keys` replay already covered in Task 3).

- [ ] **Step 1: Failing test**

```java
package com.leandrossb.nummus.idempotency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class IdempotencyMerchantNamespaceTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  private String createMerchantAndGetKey(String name) throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  private String openAccount(String bearer, String holder) throws Exception {
    return mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + bearer)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"holderName\":\"" + holder + "\"}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
  }

  @Test
  void theSameKeyExecutesIndependentlyPerMerchant() throws Exception {
    String a = createMerchantAndGetKey("Idem A");
    String b = createMerchantAndGetKey("Idem B");
    openAccount(a, "Holder A");
    openAccount(b, "Holder B");
    String sharedKey = UUID.randomUUID().toString();
    String body = "{\"holderName\":\"Shared Key Holder\"}";

    var first = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + a).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andReturn();
    var second = mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + b).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotency-Replayed"))
        .andReturn();
    // Different accounts (independent executions), NOT a replay of A's response.
    org.junit.jupiter.api.Assertions.assertNotEquals(
        first.getResponse().getHeader("Location"), second.getResponse().getHeader("Location"));
    // Within one merchant the key replays.
    mockMvc.perform(post("/v1/accounts")
            .header("Authorization", "Bearer " + b).header(KEY, sharedKey)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }

  @Test
  void operatorNamespaceStaysUniqueWithoutAMerchant() throws Exception {
    // Operator POSTs (conciliation ingest) reserve with NULL merchant; the same
    // key still replays within the operator namespace.
    String shared = UUID.randomUUID().toString();
    String body = "{\"from\":\"2026-09-19T10:00:00Z\",\"to\":\"2026-09-19T11:00:00Z\"}";
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, shared).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/conciliation/reports")
            .header(KEY, shared).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"));
  }
}
```

- [ ] **Step 2: Remote RED** — push; FAIL (second merchant's POST replays A's response — cross-namespace leak).

- [ ] **Step 3: Implement** per Interfaces. `IS NOT DISTINCT FROM` with a null UUID parameter needs an explicit cast in JdbcClient SQL: `(merchant_public_id IS NOT DISTINCT FROM :merchant::uuid)` — if the cast syntax trips the client, use `(:merchant::uuid is null and merchant_public_id is null) or merchant_public_id = :merchant::uuid` (report which).

- [ ] **Step 4: Remote GREEN + verify** — full `verify` → BUILD SUCCESS, 194 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/leandrossb/nummus/ src/test/java/com/leandrossb/nummus/
git commit -m "feat: namespace idempotency keys per merchant

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 8: ArchUnit rules for merchants

**Files:**
- Modify: `src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java`

- [ ] **Step 1: Add two rules** (append inside the class)

```java
  @ArchTest
  static final ArchRule merchantsStaySelfContained =
      noClasses().that().resideInAPackage("..merchants..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..accounts..", "..ledger..", "..payments..",
              "..webhooks..", "..conciliation..", "..psp_simulator..");
```
```java
  @ArchTest
  static final ArchRule businessModulesNeverTouchMerchants =
      noClasses().that().resideInAnyPackage("..accounts..", "..payments..", "..webhooks..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..merchants.domain..", "..merchants.infrastructure..",
              "..merchants.interfaces..");
```
(`merchants.application` is intentionally reachable — `SeedMerchant` is the migration-era bridge — and the shared `interfaces.auth` vocabulary is the sanctioned coupling everywhere. `conciliation` and `psp_simulator` stay merchant-free entirely; extend the second rule's left side with them asserting nothing changes if you prefer — the first rule already pins their independence.)

- [ ] **Step 2: Remote run** — `test -Dtest=ModuleBoundaryTest` → PASS (12 rules: 10 + 2). If `businessModulesNeverTouchMerchants` fails on `SeedMerchant` usage inside payments/webhooks test-scope… production only (`DoNotIncludeTests`) — check the failure and report, do not weaken silently.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/leandrossb/nummus/architecture/ModuleBoundaryTest.java
git commit -m "test: pin merchant module boundaries

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

### Task 9: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — Status section gains `- [x] M7 — Merchant identity and API keys` (the roadmap table gets the row); the Capabilities table gains/updates an **Authentication** row: "Merchant identity with Bearer API keys (hashed at rest, revocable); every merchant-facing resource is tenant-scoped".
- Modify: `docs/m2-backlog.md` — new section:

```markdown
## From the M7 review

M7 delivered merchant identity and API keys: operator-created merchants,
Bearer authentication (SHA-256 at rest, secret shown once, soft revocation),
and per-merchant scoping of accounts, intents, webhook endpoints, and
idempotency namespaces (operator POSTs keep a NULL-merchant namespace).
Known bounds, deliberate:

- **Operator surfaces stay open.** Merchant creation, the simulator, and
  conciliation require no credentials — an operator identity model is the
  natural next debt.
- **Key lifecycle minimums.** No expiry, rotation policy, or `last_used_at`
  tracking (a write per request); prefixes are display-only.
- **SSRF narrowed, not closed.** Webhook registration is now authenticated,
  but any http(s) target is still accepted — range rejection stays backlog.
- **No rate limiting** on authenticated routes; bearer lookups are one
  indexed query per request.
- **Merchants.application remains reachable** from business modules for the
  `SeedMerchant` bridge; retire it when a real migration-era consumer audit
  lands (or scope it behind a query port).
```

- [ ] **Step 1: Remote full verify** — `Tests run: 196, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
- [ ] **Step 2+3:** the README/backlog edits above.
- [ ] **Step 4: Commit** — `docs: mark M7 merchants and API keys complete`.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| V10 schema, seed merchant, backfills, partial uniques, roles | 1 |
| merchants domain/store/services (create, resolve-by-hash, key lifecycle) | 2 |
| interfaces.auth (filter + resolver + 401 vocabulary) + merchants REST + auth ordering | 3 |
| Accounts scoping (SQL ownership, cross-tenant 404) | 4 |
| Payments scoping (via accounts internal API) | 5 |
| Webhook endpoint scoping | 6 |
| Idempotency (merchant, key) namespace + operator NULL namespace | 7 |
| ArchUnit merchant boundaries | 8 |
| Success criteria, README, backlog | 9 |
