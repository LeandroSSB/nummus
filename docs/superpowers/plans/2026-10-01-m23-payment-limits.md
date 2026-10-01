# M23 Payment Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-merchant payment caps — nullable `maxIntentAmount` and `maxPayoutAmount`, operator-governed with attributed append-only history (the fee-schedule governance pattern), enforced at intent and payout creation with 422 rejections before any network or ledger action.

**Architecture:** Cached current values as columns on `merchants.merchant`; `merchants.payment_limits_entry` mirrors `fee_schedule_entry` for attributed history. The merchants module owns storage, the operator REST surface (`PUT/GET /v1/merchants/{id}/limits`, `GET /{id}/limits-history`), and the read consumed by payments (`MerchantsService.findPaymentLimits`). Payments enforces at both money-moving writes.

**Tech Stack:** Java 25, Spring Boot (JdbcClient, MockMvc), JUnit 5, Testcontainers (PostgreSQL), Flyway.

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits (`test:`, `feat:`, `docs:`, `fix:`).
- Money is `Money.ofBrl(...)`/`Money.of(BigDecimal, BRL)`; never `double`. BRL only.
- Null cap means unlimited; the comparison is inclusive (amount == cap passes; only strictly greater rejects).
- Enforcement ordering is contractual: the cap check runs before any network charge creation (intents) and before `ledger.lockAccount`/sufficiency (payouts) — an over-cap request must leave no row anywhere.
- History listing keyset shape, verbatim from the fee-history listing: `(:after::uuid is null or e.id < (select f.id from <table> f where f.public_id = :after)) order by e.id desc limit :limit`; `limit` 1–100 (default 50) rejected with `IllegalArgumentException("limit must be between 1 and 100: " + limit)`; fetch `limit+1`, `Next-Cursor` only on overflow.
- Module boundaries: payments never names `merchants.*` tables in SQL; it reads limits through `MerchantsService`.
- **Test-tree ripple rule:** every interface this plan extends (`MerchantStore`, `MerchantsService`) has test fakes (`grep -rn "implements MerchantStore\|implements MerchantsService" src/test/java`) — extend them in the same task, mirroring semantics, and note it in the report.
- **Verification protocol (no local Maven):** implementer subagents edit, run no build, and commit (test commit first, then implementation). The controller runs all Maven commands remotely on megalan after each task:
  `ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B <branch> origin/<branch> && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<TestClass>'`
  Branches are pushed only after a task's green commit.

---

### Task 1: `V24` migration, limits storage, and merchants service methods

**Files:**
- Create: `src/main/resources/db/migration/V24__payment_limits.sql`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimits.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimitsEntry.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/MerchantsServiceImpl.java`
- Modify: test fakes found by the ripple rule (`FakeMerchantsService`, any `MerchantStore` fake)
- Test: `src/test/java/com/leandrossb/nummus/merchants/PaymentLimitsStoreTest.java`

**Interfaces:**
- Consumes: the fee-schedule storage precedent (`findFeeSchedule`/`updateFeeSchedule`/`insertFeeScheduleEntry`/`listFeeHistory` in `JdbcClientMerchantStore`).
- Produces (exact signatures later tasks rely on):
  - `PaymentLimits` record: `PaymentLimits(Money maxIntentAmount, Money maxPayoutAmount)` with `public static PaymentLimits unlimited() { return new PaymentLimits(null, null); }` — null field = unlimited.
  - `MerchantStore`: `PaymentLimits findPaymentLimits(UUID merchantPublicId);` · `boolean updatePaymentLimits(UUID merchantPublicId, PaymentLimits limits);` · `void insertPaymentLimitsEntry(UUID merchantPublicId, PaymentLimits limits, UUID createdBy);` · `List<PaymentLimitsEntry> listPaymentLimitsHistory(UUID merchantPublicId, UUID after, int limit);`
  - `MerchantsService`: `Optional<PaymentLimits> findPaymentLimits(UUID merchantPublicId);` · `void updatePaymentLimits(UUID merchantPublicId, PaymentLimits limits, UUID actingOperatorKeyPublicId);`
  - `PaymentLimitsEntry` record mirroring `FeeHistoryEntry`'s shape: `PaymentLimitsEntry(UUID entryId, BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount, Instant validFrom, UUID createdBy, String createdByLabel)`.

- [ ] **Step 1: Write the failing store/service test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.application.PaymentLimits;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Limits round-trip: cached current values on the merchant row, an
 *  attributed append-only entry per change, newest first. */
@AutoConfigureMockMvc
class PaymentLimitsStoreTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private MerchantsService merchants;

  private UUID merchantId;
  private UUID operatorKeyPublicId;

  @BeforeEach
  void createFixtures() throws Exception {
    var operatorKey = operatorKeys.create("limits-probe", null, null);
    operatorKeyPublicId = operatorKey.publicId();
    String operatorAuth = "Bearer " + operatorKey.secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payment Limits Store Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(), "$.merchantId"));
  }

  @Test
  void freshMerchantIsUnlimitedAndChangesAppendAttributedHistory() {
    var initial = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertNull(initial.maxIntentAmount());
    assertNull(initial.maxPayoutAmount());

    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(Money.ofBrl("5000.0000"), Money.ofBrl("2000.0000")), operatorKeyPublicId);
    merchants.updatePaymentLimits(merchantId,
        new PaymentLimits(null, Money.ofBrl("1500.0000")), operatorKeyPublicId);

    var current = merchants.findPaymentLimits(merchantId).orElseThrow();
    assertNull(current.maxIntentAmount());
    assertEquals(0, current.maxPayoutAmount().compareTo(Money.ofBrl("1500.0000")));

    var history = merchants.listPaymentLimitsHistory(merchantId, null, 50);
    assertEquals(2, history.size()); // newest first
    assertNull(history.get(0).maxIntentAmount());
    assertEquals(0, history.get(0).maxPayoutAmount().compareTo(new BigDecimal("1500.0000")));
    assertEquals(operatorKeyPublicId, history.get(0).createdBy());
    assertEquals("limits-probe", history.get(0).createdByLabel());
    assertEquals(0, history.get(1).maxIntentAmount().compareTo(new BigDecimal("5000.0000")));
  }
}
```

Note: `listPaymentLimitsHistory` on `MerchantsService` (delegating to the store) is part of this task's Produces — add it alongside `findPaymentLimits` (signature `List<PaymentLimitsEntry> listPaymentLimitsHistory(UUID merchantPublicId, UUID after, int limit);`).

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/merchants/PaymentLimitsStoreTest.java
git commit -m "test: cover the payment limits store and service"
```

- [ ] **Step 3: Implement**

`src/main/resources/db/migration/V24__payment_limits.sql`:

```sql
-- M23 payment limits: per-merchant caps on single money-moving operations.
-- The fee-schedule storage shape: cached current values on the merchant row,
-- attributed append-only history beside it. NULL cap means unlimited — the
-- default for every existing and new merchant (purely additive).

alter table merchants.merchant
  add column max_intent_amount  numeric(19,4) check (max_intent_amount > 0),
  add column max_payout_amount numeric(19,4) check (max_payout_amount > 0);

create table merchants.payment_limits_entry (
  id          bigint generated always as identity primary key,
  public_id   uuid not null default gen_random_uuid() unique,
  merchant_id bigint not null references merchants.merchant(id),
  max_intent_amount  numeric(19,4),
  max_payout_amount numeric(19,4),
  valid_from  timestamptz not null default now(),
  created_by  uuid references merchants.operator_key(public_id),
  created_at  timestamptz not null default now()
);
create index payment_limits_entry_merchant_idx
  on merchants.payment_limits_entry (merchant_id, id desc);

grant select, insert on merchants.payment_limits_entry to nummus_app;
```

(No backfill insert: absent limits are simply unlimited; history starts at the first operator change. V10's table-level update grant covers the new merchant columns.)

`src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimits.java`:

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.ledger.domain.Money;

/**
 * Per-merchant caps on single money-moving operations. A null field means
 * unlimited — the default. Comparisons are inclusive: an amount equal to the
 * cap passes; only strictly greater rejects.
 */
public record PaymentLimits(Money maxIntentAmount, Money maxPayoutAmount) {

  public static PaymentLimits unlimited() {
    return new PaymentLimits(null, null);
  }
}
```

`src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimitsEntry.java`:

```java
package com.leandrossb.nummus.merchants.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One attributed payment-limits change from the merchant's append-only
 *  history, newest first in listings. createdBy — and with it
 *  createdByLabel — is null only for a migration backfill (none exists at
 *  introduction; the field mirrors FeeHistoryEntry's sentinel semantics). */
public record PaymentLimitsEntry(UUID entryId, BigDecimal maxIntentAmount,
    BigDecimal maxPayoutAmount, Instant validFrom, UUID createdBy, String createdByLabel) {
}
```

`MerchantStore` — add the four method declarations from Produces with one-line Javadocs mirroring the fee methods' style.

`JdbcClientMerchantStore` — add, mirroring the fee implementations line for line (same merchant-id resolution `insertFeeScheduleEntry` uses — read it and copy the pattern):

```java
  @Override
  public PaymentLimits findPaymentLimits(UUID merchantPublicId) {
    return jdbc.sql(
        "select max_intent_amount, max_payout_amount from merchants.merchant where public_id = :id")
        .param("id", merchantPublicId)
        .query((rs, i) -> new PaymentLimits(
            rs.getBigDecimal("max_intent_amount") == null ? null
                : Money.of(rs.getBigDecimal("max_intent_amount"), BRL),
            rs.getBigDecimal("max_payout_amount") == null ? null
                : Money.of(rs.getBigDecimal("max_payout_amount"), BRL)))
        .optional().orElse(PaymentLimits.unlimited());
  }

  @Override
  public boolean updatePaymentLimits(UUID merchantPublicId, PaymentLimits limits) {
    return jdbc.sql("""
            update merchants.merchant
            set max_intent_amount = :maxIntent, max_payout_amount = :maxPayout
            where public_id = :id
            """)
        .param("maxIntent", limits.maxIntentAmount() == null ? null : limits.maxIntentAmount().amount())
        .param("maxPayout", limits.maxPayoutAmount() == null ? null : limits.maxPayoutAmount().amount())
        .param("id", merchantPublicId)
        .update() == 1;
  }

  @Override
  public void insertPaymentLimitsEntry(UUID merchantPublicId, PaymentLimits limits, UUID createdBy) {
    jdbc.sql("""
        insert into merchants.payment_limits_entry
          (merchant_id, max_intent_amount, max_payout_amount, created_by)
        select m.id, :maxIntent, :maxPayout, :createdBy
        from merchants.merchant m where m.public_id = :id
        """)
        .param("maxIntent", limits.maxIntentAmount() == null ? null : limits.maxIntentAmount().amount())
        .param("maxPayout", limits.maxPayoutAmount() == null ? null : limits.maxPayoutAmount().amount())
        .param("createdBy", createdBy)
        .param("id", merchantPublicId)
        .update();
  }

  @Override
  public List<PaymentLimitsEntry> listPaymentLimitsHistory(UUID merchantPublicId, UUID after, int limit) {
    return jdbc.sql("""
        select e.public_id, e.max_intent_amount, e.max_payout_amount, e.valid_from, e.created_by,
          k.label as created_by_label
        from merchants.payment_limits_entry e
        join merchants.merchant m on m.id = e.merchant_id
        left join merchants.operator_key k on k.public_id = e.created_by
        where m.public_id = :merchantPublicId
          and (:after::uuid is null
               or e.id < (select f.id from merchants.payment_limits_entry f
                          where f.public_id = :after))
        order by e.id desc
        limit :limit
        """)
        .param("merchantPublicId", merchantPublicId)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> new PaymentLimitsEntry(rs.getObject("public_id", UUID.class),
            rs.getBigDecimal("max_intent_amount"), rs.getBigDecimal("max_payout_amount"),
            rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
            rs.getObject("created_by", UUID.class), rs.getString("created_by_label")))
        .list();
  }
```

(If `insertFeeScheduleEntry` resolves `merchant_id` differently, mirror its exact resolution instead — the goal is pattern identity. Add the `Money`/`PaymentLimits` imports; the class already imports `OffsetDateTime` for the fee history mapper.)

`MerchantsService` — add the three method declarations from Produces (find/update/listHistory) with Javadocs mirroring the fee methods.

`MerchantsServiceImpl` — add:

```java
  @Override
  @Transactional
  public void updatePaymentLimits(UUID publicId, PaymentLimits limits, UUID actingOperatorKey) {
    store.insertPaymentLimitsEntry(publicId, limits, actingOperatorKey);
    store.updatePaymentLimits(publicId, limits);
  }
```

plus `@Transactional(readOnly = true)` delegating `findPaymentLimits` (returning `store.findPaymentLimits(...)` — empty only for an unknown merchant, so resolve the service-level Optional by first checking the merchant exists exactly the way `updateFeeSchedule`'s callers do; simplest faithful shape: `return store.findMerchant(publicId).isPresent() ? Optional.of(store.findPaymentLimits(publicId)) : Optional.empty();` — adapt `findMerchant` to the store's actual finder name) and `listPaymentLimitsHistory` delegating to the store.

Test fakes (ripple rule): `FakeMerchantsService` gains the three `MerchantsService` methods (in-memory map or fixed `PaymentLimits.unlimited()`; history can return `List.of()` — nothing unit-level asserts it); any test `MerchantStore` fake gains the four store methods the same way.

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java src/main/resources
git commit -m "feat: store per-merchant payment limits with attributed history"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=PaymentLimitsStoreTest` on megalan. Expected: 1 test PASS (Flyway V24 applies clean).

---

### Task 2: Operator REST — limits and limits-history

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/UpdateLimitsRequest.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsResponse.java`
- Create: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsHistoryResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/PaymentLimitsRestApiTest.java`

**Interfaces:**
- Consumes: Task 1's `MerchantsService.findPaymentLimits`/`updatePaymentLimits`/`listPaymentLimitsHistory` and `PaymentLimits`/`PaymentLimitsEntry`.
- Produces: `PUT /v1/merchants/{id}/limits` (operator, `@Idempotent`, `200` + `LimitsResponse`); `GET /v1/merchants/{id}/limits` (`200` + `LimitsResponse`, nulls as JSON null); `GET /v1/merchants/{id}/limits-history` (keyset, `Next-Cursor`).

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;

/** Operator-governed limits over REST: set, read, attributed history —
 *  and nothing a merchant key can reach. */
@AutoConfigureMockMvc
class PaymentLimitsRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private String operatorAuth;
  private UUID merchantId;
  private String merchantKey;

  @BeforeEach
  void createFixtures() throws Exception {
    operatorAuth = "Bearer " + operatorKeys.create("limits-rest-probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payment Limits Rest Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
  }

  @Test
  void putStoresLimitsAndGetReturnsThem() throws Exception {
    mockMvc.perform(get("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").doesNotExist())
        .andExpect(jsonPath("$.maxPayoutAmount").doesNotExist());

    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":5000.0000,\"maxPayoutAmount\":2000.0000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").value(5000.0000))
        .andExpect(jsonPath("$.maxPayoutAmount").value(2000.0000));

    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxPayoutAmount\":1500.0000}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxIntentAmount").doesNotExist())
        .andExpect(jsonPath("$.maxPayoutAmount").value(1500.0000));
  }

  @Test
  void historyListsAttributedChangesNewestFirst() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":100.0000}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/v1/merchants/{id}/limits-history", merchantId)
            .header("Authorization", operatorAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].maxIntentAmount").value(100.0000))
        .andExpect(jsonPath("$[0].createdByLabel").value("limits-rest-probe"));
  }

  @Test
  void invalidBodiesAndUnknownMerchantsAreRejected() throws Exception {
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":-5}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/v1/merchants/{id}/limits", UUID.randomUUID())
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxIntentAmount\":5}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void merchantKeysCannotReachTheOperatorSurface() throws Exception {
    mockMvc.perform(get("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isForbidden());
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
  }
}
```

(If the operator gate rejects merchant keys with 401 rather than 403 on these routes, adjust the two expected statuses to what `GET /v1/merchants/{id}` (operator route) yields for a merchant key today — verify once by reading the auth filter or an existing operator-route test — and keep both asserts consistent.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/merchants/PaymentLimitsRestApiTest.java
git commit -m "test: cover the payment limits operator REST surface"
```

- [ ] **Step 3: Implement the DTOs and controller endpoints**

`UpdateLimitsRequest.java`:

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.PaymentLimits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** A limits replacement over the wire: absent or null field = unlimited. */
public record UpdateLimitsRequest(
    @DecimalMin(value = "0", inclusive = false, message = "maxIntentAmount must be > 0")
    @Digits(integer = 15, fraction = 4) BigDecimal maxIntentAmount,
    @DecimalMin(value = "0", inclusive = false, message = "maxPayoutAmount must be > 0")
    @Digits(integer = 15, fraction = 4) BigDecimal maxPayoutAmount) {

  public PaymentLimits limits() {
    return new PaymentLimits(
        maxIntentAmount == null ? null : Money.of(maxIntentAmount,
            java.util.Currency.getInstance("BRL")),
        maxPayoutAmount == null ? null : Money.of(maxPayoutAmount,
            java.util.Currency.getInstance("BRL")));
  }
}
```

`LimitsResponse.java`:

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.PaymentLimits;
import java.math.BigDecimal;

/** REST view of a merchant's payment limits; a null field means unlimited. */
public record LimitsResponse(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount) {

  public static LimitsResponse from(PaymentLimits limits) {
    return new LimitsResponse(
        limits.maxIntentAmount() == null ? null : limits.maxIntentAmount().amount(),
        limits.maxPayoutAmount() == null ? null : limits.maxPayoutAmount().amount());
  }
}
```

`LimitsHistoryResponse.java`:

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.PaymentLimitsEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One attributed limits change, exactly as recorded. */
public record LimitsHistoryResponse(UUID entryId, BigDecimal maxIntentAmount,
    BigDecimal maxPayoutAmount, Instant validFrom, UUID createdBy, String createdByLabel) {

  public static LimitsHistoryResponse from(PaymentLimitsEntry entry) {
    return new LimitsHistoryResponse(entry.entryId(), entry.maxIntentAmount(),
        entry.maxPayoutAmount(), entry.validFrom(), entry.createdBy(), entry.createdByLabel());
  }
}
```

In `MerchantsController`, mirror the three fee endpoints' shape exactly — add after `feeHistory`:

```java
  @Idempotent
  @PutMapping("/{id}/limits")
  ResponseEntity<LimitsResponse> updateLimits(AuthenticatedOperator operator,
      @PathVariable UUID id, @Valid @RequestBody UpdateLimitsRequest request) {
    merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    merchants.updatePaymentLimits(id, request.limits(), operator.keyPublicId());
    return ResponseEntity.ok(LimitsResponse.from(merchants.findPaymentLimits(id).orElseThrow()));
  }

  @GetMapping("/{id}/limits")
  LimitsResponse limits(AuthenticatedOperator operator, @PathVariable UUID id) {
    merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    return LimitsResponse.from(merchants.findPaymentLimits(id).orElseThrow());
  }

  @GetMapping("/{id}/limits-history")
  ResponseEntity<List<LimitsHistoryResponse>> limitsHistory(AuthenticatedOperator operator,
      @PathVariable UUID id, @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    merchants.find(id).orElseThrow(() -> new UnknownMerchantException(id));
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned entry's public id) only when the probe found an extra row.
    var page = merchants.listPaymentLimitsHistory(id, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(LimitsHistoryResponse::from).toList());
    }
    var cursor = page.get(limit - 1).entryId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(LimitsHistoryResponse::from).toList());
  }
```

Note: if `MerchantsServiceImpl.updatePaymentLimits` validates the merchant's existence itself (it must, so `updateFeeSchedule`'s write cannot target an unknown id — mirror what `updateFeeSchedule` does about unknown merchants; if the fee path leaves that to the controller's `find` precheck, limits does the same and the service writes nothing for an unknown id because the entry insert's `select` matches no row).

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/UpdateLimitsRequest.java \
  src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsResponse.java \
  src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsHistoryResponse.java \
  src/main/java/com/leandrossb/nummus/merchants/interfaces/MerchantsController.java
git commit -m "feat: govern payment limits over the operator REST surface"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=PaymentLimitsRestApiTest` on megalan. Expected: 4 tests PASS.

---

### Task 3: Enforcement at intent and payout creation

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/domain/PaymentLimitExceededException.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/PaymentLimitsEnforcementTest.java`

**Interfaces:**
- Consumes: Task 1's `MerchantsService.findPaymentLimits` + `PaymentLimits.unlimited()`.
- Produces: `PaymentLimitExceededException(UUID merchantPublicId, Money requested, Money cap)` in `payments.domain`; handler maps it to `422`.

- [ ] **Step 1: Write the failing enforcement test**

```java
package com.leandrossb.nummus.payments;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.PayoutsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Caps bound single money-moving operations: over-cap writes reject 422 and
 *  leave no row anywhere; at-cap passes; unset is unlimited. */
@AutoConfigureMockMvc
class PaymentLimitsEnforcementTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private String operatorAuth;
  private String merchantKey;
  private UUID merchantId;
  private UUID accountId;

  @BeforeEach
  void createFixtures() throws Exception {
    operatorAuth = "Bearer " + operatorKeys.create("enforce-probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Limits Enforcement Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    accountId = accountsService.open(merchantId, new OpenAccountCommand("Enforcement Account"))
        .publicId();
  }

  private void setLimits(String intentCap, String payoutCap) throws Exception {
    String content = "{";
    if (intentCap != null) {
      content += "\"maxIntentAmount\":" + intentCap;
    }
    if (payoutCap != null) {
      content += (content.length() > 1 ? "," : "") + "\"maxPayoutAmount\":" + payoutCap;
    }
    mockMvc.perform(put("/v1/merchants/{id}/limits", merchantId)
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(content + "}"))
        .andExpect(status().isOk());
  }

  @Test
  void overCapIntentRejects422AndLeavesNoRow() throws Exception {
    setLimits("100.0000", null);
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":100.0001}"))
        .andExpect(status().isUnprocessableEntity());
    // The M22 listing proves no intent row exists for the rejection.
    mockMvc.perform(get("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void atCapIntentPassesInclusively() throws Exception {
    setLimits("100.0000", null);
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":100.0000}"))
        .andExpect(status().isCreated());
  }

  @Test
  void overCapPayoutRejects422BeforeAnyReservationOrTransfer() throws Exception {
    setLimits(null, "50.0000");
    var funding = payments.create(merchantId,
        new CreateIntentCommand(accountId, Money.ofBrl("500.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    var destination = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);

    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":50.0001,"
                + "\"bankAccountId\":\"" + destination.publicId() + "\"}"))
        .andExpect(status().isUnprocessableEntity());

    // No payout row, and the balance is untouched (no reservation taken).
    mockMvc.perform(get("/v1/payouts")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/accounts/{id}/balance", accountId)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.amount").value(500.0000));
  }

  @Test
  void unsetCapsStayUnlimited() throws Exception {
    mockMvc.perform(post("/v1/payment-intents")
            .header("Authorization", "Bearer " + merchantKey)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + accountId + "\",\"amount\":1000000.0000}"))
        .andExpect(status().isCreated());
  }
}
```

(`put` is among the static imports at the top of the file; the request field names above are verified against `CreateIntentRequest` — `accountId`/`amount` — and `CreatePayoutRequest` — `accountId`/`amount`/`bankAccountId`.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/PaymentLimitsEnforcementTest.java
git commit -m "test: cover payment limits enforcement at the money-moving writes"
```

- [ ] **Step 3: Implement the exception, handler, and the two checks**

`PaymentLimitExceededException.java`:

```java
package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/**
 * Thrown when a single money-moving operation exceeds the merchant's cap for
 * it. Carries the requested amount and the cap the decision was made against;
 * the comparison is inclusive, so reaching here means strictly over.
 */
public class PaymentLimitExceededException extends RuntimeException {

  private final UUID merchantPublicId;
  private final Money requested;
  private final Money cap;

  public PaymentLimitExceededException(UUID merchantPublicId, Money requested, Money cap) {
    super("payment limit exceeded for merchant " + merchantPublicId + ": requested "
        + requested.amount().toPlainString() + " " + requested.currency().getCurrencyCode()
        + ", cap " + cap.amount().toPlainString() + " " + cap.currency().getCurrencyCode());
    this.merchantPublicId = merchantPublicId;
    this.requested = requested;
    this.cap = cap;
  }

  public UUID merchantPublicId() {
    return merchantPublicId;
  }

  public Money requested() {
    return requested;
  }

  public Money cap() {
    return cap;
  }
}
```

In `GlobalExceptionHandler`, beside `insufficientFunds` (same file, imports added):

```java
  @ExceptionHandler(PaymentLimitExceededException.class)
  ProblemDetail paymentLimitExceeded(PaymentLimitExceededException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }
```

In `PaymentsServiceImpl.create`, after the `AccountStatus.ACTIVE` check and before `network.createCharge(cmd.amount())`:

```java
    // Risk cap before any external action: an over-cap request must not
    // create a network charge. Unset limits are unlimited; the comparison is
    // inclusive — equal passes.
    var limits = merchants.findPaymentLimits(merchantPublicId).orElse(PaymentLimits.unlimited());
    if (limits.maxIntentAmount() != null
        && cmd.amount().compareTo(limits.maxIntentAmount()) > 0) {
      throw new PaymentLimitExceededException(merchantPublicId, cmd.amount(),
          limits.maxIntentAmount());
    }
```

(with the `com.leandrossb.nummus.merchants.application.PaymentLimits` and
`com.leandrossb.nummus.payments.domain.PaymentLimitExceededException` imports; `merchants` is already injected).

In `PayoutsServiceImpl.create`, after the destination resolution and the account-status check, and BEFORE `ledger.lockAccount(...)`:

```java
    // Risk cap before the ledger lock and any reservation or network
    // transfer: an over-cap request must leave no trace. Inclusive — equal
    // passes.
    var limits = merchants.findPaymentLimits(merchantPublicId).orElse(PaymentLimits.unlimited());
    if (limits.maxPayoutAmount() != null
        && cmd.amount().compareTo(limits.maxPayoutAmount()) > 0) {
      throw new PaymentLimitExceededException(merchantPublicId, cmd.amount(),
          limits.maxPayoutAmount());
    }
```

(same imports; `merchants` is already injected there — it resolves the fee schedule today).

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/domain/PaymentLimitExceededException.java \
  src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java \
  src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java \
  src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java
git commit -m "feat: enforce per-merchant payment limits at intent and payout creation"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=PaymentLimitsEnforcementTest` on megalan. Expected: 4 tests PASS.

---

### Task 4: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:** Consumes the shipped Tasks 1–3 surface. Produces documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table:
- **Instant payments** row: after "Payment intents and Pix-style dynamic charges: create, expire, settle;", insert "per-merchant amount caps," (keep the rest unchanged).
- **Authentication** row: extend the segment "fee-schedule changes are attributed, append-only history" to "fee-schedule and payment-limit changes are attributed, append-only history" (keep the rest unchanged).

In the Status list, append after the M22 line:

```markdown
- [x] M23 — Payment limits (per-merchant caps)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M23 payment limits complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify` via the Global Constraints command, minus `-Dtest`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
