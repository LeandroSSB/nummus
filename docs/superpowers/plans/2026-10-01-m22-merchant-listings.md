# M22 Merchant Listings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merchant-scoped, keyset-paginated listings for payment intents, payouts, refunds, and bank accounts — the deliveries-listing pattern (`after` + `limit`, fetch `limit+1`, `Next-Cursor` header) applied to every merchant resource.

**Architecture:** Each module's repository gains a keyset list query scoped by the merchant's account ids (obtained through `AccountsService`, never by cross-module table access); controllers probe `limit+1` and emit `Next-Cursor` exactly like `WebhookDeliveriesController`. Batch helpers (`quotesFor`, `refundedTotals`, `listPublicIds`) keep the intents listing at three queries per page.

**Tech Stack:** Java 25, Spring Boot (JdbcClient, MockMvc), JUnit 5, Testcontainers (PostgreSQL).

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits (`test:`, `feat:`, `docs:`, `fix:`).
- Keyset shape, verbatim from the deliveries listing: `(:after::uuid is null or x.id < (select x2.id from <table> x2 where x2.public_id = :after)) order by x.id desc limit :limit`; `limit` between 1 and 100 (default 50) rejected otherwise with `IllegalArgumentException("limit must be between 1 and 100: " + limit)`; fetch `limit+1`, return `limit`, emit `Next-Cursor` (last returned item's public id) only when the probe found an extra row.
- No cross-module table access: payments never names `accounts.payment_account` in SQL — the merchant's account public ids come from `AccountsService`.
- An `after` cursor that does not resolve yields an empty page, no `Next-Cursor`.
- Money is `Money.ofBrl(...)`/`Money.of(BigDecimal, BRL)`; never `double`. No DB migration; `V23` stays the head. No new webhook events.
- REST listings return the existing by-id DTOs (`IntentResponse`, `PayoutResponse`, `RefundResponse`, `BankAccountResponse`) as JSON arrays.
- Test isolation: every listing test class creates a FRESH merchant per test in `@BeforeEach` (the pagination-test precedent), so accumulated rows from earlier tests belong to other merchants and never leak into a walk.
- **Verification protocol (no local Maven):** implementer subagents edit, run no build, and commit (test commit first, then implementation, matching repo history). The controller runs all Maven commands remotely on megalan after each task:
  `ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B <branch> origin/<branch> && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<TestClass>'`
  Branches are pushed only after a task's green commit.

---

### Task 1: Batch helpers — account ids, fee quotes, refunded totals

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/application/AccountsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/accounts/infrastructure/JdbcClientAccountsRepository.java`
- Modify: `src/test/java/com/leandrossb/nummus/accounts/application/InMemoryAccountsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/FeeQuotes.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientRefundsRepository.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/BatchHelpersTest.java`

**Interfaces:**
- Consumes: existing `PaymentIntent` (with `feeAmount()`), `FeeQuote(Money fee, Money netAmount)`, `FeeCalculator.compute`, `FeeSchedule`, `Money`.
- Produces (exact signatures later tasks rely on):
  - `AccountsService`: `List<UUID> listPublicIds(UUID merchantPublicId);`
  - `FeeQuotes`: `Map<UUID, FeeQuote> quotesFor(UUID merchantPublicId, List<PaymentIntent> intents);`
  - `RefundsService`: `Map<UUID, Money> refundedTotals(List<UUID> intentPublicIds);`

- [ ] **Step 1: Write the failing test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.FeeQuotes;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The page-composition helpers the listings build on: per-merchant account
 *  ids, one-schedule fee quotes for a batch, and grouped refunded totals. */
@AutoConfigureMockMvc
class BatchHelpersTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private FeeQuotes quotes;

  private UUID merchantId;
  private UUID otherMerchantId;
  private PaymentIntent settled;

  @BeforeEach
  void createFixtures() throws Exception {
    merchantId = createMerchant("Batch Helpers Merchant");
    otherMerchantId = createMerchant("Batch Helpers Other Merchant");
    var account = accountsService.open(merchantId, new OpenAccountCommand("Batch Helpers Account"));
    settled = createAndSettleIntent(account.publicId(), "100.0000");
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("25.0000"), Duration.ofMinutes(10)));
    refunds.create(merchantId, settled.publicId(),
        new CreateRefundCommand(Money.ofBrl("5.0000"), Duration.ofMinutes(10)));
    payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));
  }

  @Test
  void listPublicIdsReturnsOnlyThatMerchantsAccounts() {
    var ids = accountsService.listPublicIds(merchantId);
    assertEquals(1, ids.size());
    assertEquals(settled.accountPublicId(), ids.get(0));
    assertEquals(List.of(), accountsService.listPublicIds(otherMerchantId));
  }

  @Test
  void quotesForUsesSettledFactAndQuotesOpenIntentsFromOneSweep() {
    var open = payments.create(merchantId,
        new CreateIntentCommand(settled.accountPublicId(), Money.ofBrl("40.0000"), Duration.ofMinutes(10)));
    var map = quotes.quotesFor(merchantId, List.of(settled, open));
    assertEquals(2, map.size());
    // Settled intent: the charged fact (zero schedule → fee 0, net = gross).
    assertEquals(0, map.get(settled.publicId()).fee().compareTo(Money.ofBrl("0.0000")));
    assertEquals(0, map.get(settled.publicId()).netAmount().compareTo(Money.ofBrl("100.0000")));
    // Open intent: the current schedule's quote (zero schedule here).
    assertEquals(0, map.get(open.publicId()).netAmount().compareTo(Money.ofBrl("40.0000")));
    assertEquals(Map.of(), quotes.quotesFor(merchantId, List.of()));
  }

  @Test
  void refundedTotalsGroupHeldAndSettledRefundsPerIntent() {
    var unknown = UUID.randomUUID();
    var map = refunds.refundedTotals(List.of(settled.publicId(), unknown));
    assertEquals(2, map.size());
    assertEquals(0, map.get(settled.publicId()).compareTo(Money.ofBrl("30.0000")));
    assertEquals(0, map.get(unknown).compareTo(Money.ofBrl("0.0000")));
  }

  private PaymentIntent createAndSettleIntent(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    return payments.get(merchantId, intent.publicId());
  }

  private UUID createMerchant(String name) throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return UUID.fromString(com.jayway.jsonpath.JsonPath
        .read(created.getResponse().getContentAsString(), "$.merchantId"));
  }
}
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/BatchHelpersTest.java
git commit -m "test: cover the page-composition batch helpers"
```

- [ ] **Step 3: Implement the three helpers**

`AccountsService` — add:

```java
  /** Every account public id the merchant owns; listings scope by these. */
  List<UUID> listPublicIds(UUID merchantPublicId);
```

`AccountsRepository` — add:

```java
  List<UUID> findPublicIdsByMerchant(UUID merchantPublicId);
```

`JdbcClientAccountsRepository` — add (mirror the file's existing JdbcClient style):

```java
  @Override
  public List<UUID> findPublicIdsByMerchant(UUID merchantPublicId) {
    return jdbc.sql("select public_id from accounts.payment_account where merchant_public_id = :merchantPublicId")
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> rs.getObject("public_id", UUID.class))
        .list();
  }
```

`AccountsServiceImpl` — add:

```java
  @Override
  @Transactional(readOnly = true)
  public List<UUID> listPublicIds(UUID merchantPublicId) {
    return repository.findPublicIdsByMerchant(merchantPublicId);
  }
```

`InMemoryAccountsRepository` (test fake) — add, adapting `store` to the fake's actual storage field:

```java
  @Override
  public List<UUID> findPublicIdsByMerchant(UUID merchantPublicId) {
    return store.values().stream()
        .filter(a -> a.merchantPublicId().equals(merchantPublicId))
        .map(PaymentAccount::publicId)
        .toList();
  }
```

`FeeQuotes` — add, with imports `java.util.HashMap`, `java.util.List`, and `java.util.Map`:

```java
  /** Quotes for a page: settled intents carry their charged fact; open ones
   *  are priced by one schedule sweep, not one lookup per row. */
  public Map<UUID, FeeQuote> quotesFor(UUID merchantPublicId, List<PaymentIntent> intents) {
    if (intents.isEmpty()) {
      return Map.of();
    }
    FeeSchedule schedule = null;
    var result = new HashMap<UUID, FeeQuote>(intents.size());
    for (PaymentIntent intent : intents) {
      if (intent.feeAmount() != null) {
        result.put(intent.publicId(),
            new FeeQuote(intent.feeAmount(), intent.amount().subtract(intent.feeAmount())));
      } else {
        if (schedule == null) {
          schedule = merchants.findFeeSchedule(merchantPublicId).orElse(FeeSchedule.ZERO);
        }
        var current = FeeCalculator.compute(intent.amount(), schedule);
        result.put(intent.publicId(), new FeeQuote(current.fee(), current.net()));
      }
    }
    return result;
  }
```

`RefundsRepository` — add:

```java
  Map<UUID, Money> findRefundedTotals(List<UUID> intentPublicIds);
```

`JdbcClientRefundsRepository` — add (the single-intent `refundedTotal` predicate, grouped; the class already has a `BRL` constant — reuse it):

```java
  @Override
  public Map<UUID, Money> findRefundedTotals(List<UUID> intentPublicIds) {
    if (intentPublicIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Money> result = new HashMap<>();
    jdbc.sql("""
        select intent_public_id, sum(amount) from payments.refund
        where intent_public_id in (:intentPublicIds)
          and status in ('REQUESTED', 'SETTLED')
        group by intent_public_id
        """)
        .param("intentPublicIds", intentPublicIds)
        .query((rs, i) -> {
          result.put(rs.getObject("intent_public_id", UUID.class),
              Money.of(rs.getBigDecimal(2), BRL));
          return (Money) null;
        })
        .list();
    return result;
  }
```

(with imports `java.util.HashMap`, `java.util.List`, `java.util.Map` as needed; if the class lacks a `BRL` constant, add `private static final Currency BRL = Currency.getInstance("BRL");` mirroring other repositories).

`RefundsService` — add:

```java
  /** Refunded totals (held + settled refunds) for a page of intents; missing
   *  intents map to zero. */
  Map<UUID, Money> refundedTotals(List<UUID> intentPublicIds);
```

`RefundsServiceImpl` — add:

```java
  @Override
  @Transactional(readOnly = true)
  public Map<UUID, Money> refundedTotals(List<UUID> intentPublicIds) {
    Objects.requireNonNull(intentPublicIds, "intentPublicIds must not be null");
    var held = repository.findRefundedTotals(intentPublicIds);
    var result = new HashMap<UUID, Money>(intentPublicIds.size());
    for (UUID id : intentPublicIds) {
      result.put(id, held.getOrDefault(id, Money.of(BigDecimal.ZERO, BRL)));
    }
    return result;
  }
```

(with `java.util.HashMap`, `java.util.Map`, `java.math.BigDecimal` imports and the `BRL` constant as above if missing).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: add page-composition batch helpers"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=BatchHelpersTest` on megalan. Expected: 3 tests PASS.

---

### Task 2: Intents listing end to end

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/PaymentIntentsListingRestApiTest.java`

**Interfaces:**
- Consumes: Task 1 helpers (`listPublicIds`, `quotesFor`, `refundedTotals`); existing `mapIntent` row mapper and `IntentResponse.from(intent, quote, refundedTotal)`.
- Produces: `GET /v1/payment-intents?status=&account=&after=&limit=` → `200` JSON array of `IntentResponse`, `Next-Cursor` header when a next page exists.

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The merchant's own intents, newest first, chained by Next-Cursor without
 *  overlap or gaps; filters intersect; foreign cursors resolve to nothing. */
@AutoConfigureMockMvc
class PaymentIntentsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  private String merchantKey;
  private UUID merchantId;
  private UUID accountA;
  private UUID accountB;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Intents Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Listing B")).publicId();
    for (int i = 0; i < 3; i++) {
      payments.create(merchantId,
          new CreateIntentCommand(accountA, Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    }
    payments.create(merchantId,
        new CreateIntentCommand(accountB, Money.ofBrl("20.0000"), Duration.ofMinutes(10)));
  }

  /** Walks the whole listing through Next-Cursor at the given page size,
   *  returning every public id in page order. */
  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/payment-intents" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        headers.add(cursor);
      }
    }
    // Every non-final page emitted a distinct cursor.
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryIntentExactlyOnceNewestFirst() throws Exception {
    // 5 = 3 on A + 1 on B from the fixture, plus this one created just now
    // (the newest row the walk must surface first).
    var newest = payments.create(merchantId,
        new CreateIntentCommand(accountB, Money.ofBrl("20.0000"), Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size()); // no overlap across pages
    assertEquals(newest.publicId().toString(), ids.get(0)); // newest first
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/payment-intents?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/payment-intents?status=CREATED&account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/payment-intents?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/payment-intents?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/PaymentIntentsListingRestApiTest.java
git commit -m "test: cover the payment-intents listing surface"
```

- [ ] **Step 3: Implement repository, service, controller**

`PaymentsRepository` — add:

```java
  List<PaymentIntent> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit);
```

`JdbcClientPaymentsRepository` — add (same column list as `findByPublicId`):

```java
  @Override
  public List<PaymentIntent> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, status, charge_public_id,
               expires_at, created_at, settled_at, journal_transaction_public_id, fee_amount
        from payments.payment_intent
        where account_public_id in (:accountPublicIds)
          and (:status::text is null or status = :status)
          and (:account::uuid is null or account_public_id = :account)
          and (:after::uuid is null
               or id < (select i2.id from payments.payment_intent i2 where i2.public_id = :after))
        order by id desc
        limit :limit
        """)
        .param("accountPublicIds", accountPublicIds)
        .param("status", status)
        .param("account", account)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> mapIntent(rs))
        .list();
  }
```

`PaymentsService` — add:

```java
  /** The merchant's intents, newest first, keyset-paginated (see WebhookDeliveriesController). */
  List<PaymentIntent> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit);
```

`PaymentsServiceImpl` — add (it scopes reads through accounts today; if it does not already inject `AccountsService`, add the constructor parameter and `accounts` field — the class already follows constructor injection, and Spring wires the bean):

```java
  @Override
  @Transactional(readOnly = true)
  public List<PaymentIntent> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit) {
    var accountIds = accounts.listPublicIds(merchantPublicId);
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return repository.listByAccounts(accountIds, status, account, after, limit);
  }
```

`PaymentsController` — add the collection getter and a shared composer, with imports `java.util.List` and `org.springframework.web.bind.annotation.RequestParam`:

```java
  @GetMapping
  ResponseEntity<List<IntentResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID account,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned intent's public id) only when the probe found an extra row.
    var page = payments.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(compose(merchant, page));
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(compose(merchant, page.subList(0, limit)));
  }

  /** The by-id shape without per-row lookups: one schedule sweep, one grouped
   *  refunded-totals query, then the same IntentResponse the single read uses. */
  private List<IntentResponse> compose(AuthenticatedMerchant merchant, List<PaymentIntent> page) {
    var quotesForPage = quotes.quotesFor(merchant.merchantPublicId(), page);
    var refunded = refunds.refundedTotals(
        page.stream().map(PaymentIntent::publicId).toList());
    return page.stream()
        .map(intent -> IntentResponse.from(intent, quotesForPage.get(intent.publicId()),
            refunded.get(intent.publicId())))
        .toList();
  }
```

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/application/PaymentsService.java \
  src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java \
  src/main/java/com/leandrossb/nummus/payments/interfaces/PaymentsController.java
git commit -m "feat: list payment intents with keyset pagination"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=PaymentIntentsListingRestApiTest` on megalan. Expected: 4 tests PASS.

---

### Task 3: Payouts listing end to end

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PayoutsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPayoutsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PayoutsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/PayoutsController.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/PayoutsListingRestApiTest.java`

**Interfaces:**
- Consumes: Task 1 `AccountsService.listPublicIds`; existing `mapPayout`, `PayoutResponse.from(Payout)`, `ApiDrivers.registerVerifiedBankAccount`.
- Produces: `GET /v1/payouts?status=&account=&after=&limit=` → `200` JSON array of `PayoutResponse` + `Next-Cursor`.

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The merchant's own payouts, newest first, chained by Next-Cursor; filters
 *  intersect; foreign cursors resolve to nothing. */
@AutoConfigureMockMvc
class PayoutsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private PayoutsService payouts;

  @Autowired
  private BankAccountsService bankAccounts;

  @Autowired
  private SimulatorService simulator;

  private String merchantKey;
  private UUID merchantId;
  private UUID accountA;
  private UUID accountB;
  private UUID destinationId;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Payouts Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Payout Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Payout Listing B")).publicId();
    // Fund account A by settling one intent (zero fee schedule → net = gross).
    var funding = payments.create(merchantId,
        new CreateIntentCommand(accountA, Money.ofBrl("500.0000"), Duration.ofMinutes(10)));
    simulator.pay(funding.chargePublicId());
    payments.get(merchantId, funding.publicId());
    destinationId = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId).publicId();
    for (int i = 0; i < 3; i++) {
      payouts.create(merchantId, new CreatePayoutCommand(accountA, Money.ofBrl("50.0000"),
          destinationId, Duration.ofMinutes(10)));
    }
    payouts.create(merchantId, new CreatePayoutCommand(accountB, Money.ofBrl("20.0000"),
        destinationId, Duration.ofMinutes(10)));
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/payouts" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        headers.add(cursor);
      }
    }
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryPayoutExactlyOnceNewestFirst() throws Exception {
    var newest = payouts.create(merchantId, new CreatePayoutCommand(accountB,
        Money.ofBrl("20.0000"), destinationId, Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/payouts?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/payouts?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/v1/payouts?status=REQUESTED&account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/payouts?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/payouts?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/payouts?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/PayoutsListingRestApiTest.java
git commit -m "test: cover the payouts listing surface"
```

- [ ] **Step 3: Implement**

`PayoutsRepository` — add:

```java
  List<Payout> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit);
```

`JdbcClientPayoutsRepository` — add (same column list as its `findByPublicId`):

```java
  @Override
  public List<Payout> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    return jdbc.sql("""
        select public_id, account_public_id, amount, destination_bank_key,
               bank_account_public_id, status,
               transfer_public_id, expires_at, created_at, settled_at, fee_amount,
               request_transaction_public_id, execute_transaction_public_id,
               return_transaction_public_id
        from payments.payout
        where account_public_id in (:accountPublicIds)
          and (:status::text is null or status = :status)
          and (:account::uuid is null or account_public_id = :account)
          and (:after::uuid is null
               or id < (select p2.id from payments.payout p2 where p2.public_id = :after))
        order by id desc
        limit :limit
        """)
        .param("accountPublicIds", accountPublicIds)
        .param("status", status)
        .param("account", account)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> mapPayout(rs))
        .list();
  }
```

`PayoutsService` — add:

```java
  /** The merchant's payouts, newest first, keyset-paginated. */
  List<Payout> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit);
```

`PayoutsServiceImpl` — add (it already injects `AccountsService accounts`):

```java
  @Override
  @Transactional(readOnly = true)
  public List<Payout> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit) {
    var accountIds = accounts.listPublicIds(merchantPublicId);
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return repository.listByAccounts(accountIds, status, account, after, limit);
  }
```

`PayoutsController` — add, with imports `java.util.List` and `org.springframework.web.bind.annotation.RequestParam` (adjust `payouts` to the controller's actual injected field name):

```java
  @GetMapping
  ResponseEntity<List<PayoutResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID account,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned payout's public id) only when the probe found an extra row.
    var page = payouts.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(PayoutResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(PayoutResponse::from).toList());
  }
```

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/application/PayoutsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPayoutsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/application/PayoutsService.java \
  src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java \
  src/main/java/com/leandrossb/nummus/payments/interfaces/PayoutsController.java
git commit -m "feat: list payouts with keyset pagination"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=PayoutsListingRestApiTest` on megalan. Expected: 4 tests PASS.

---

### Task 4: Refunds listing end to end

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientRefundsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsService.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/interfaces/RefundsController.java`
- Test: `src/test/java/com/leandrossb/nummus/payments/RefundsListingRestApiTest.java`

**Interfaces:**
- Consumes: Task 1 `AccountsService.listPublicIds`; existing `mapRefund`, `RefundResponse.from(Refund)`.
- Produces: `GET /v1/refunds?status=&account=&after=&limit=` → `200` JSON array of `RefundResponse` + `Next-Cursor`.

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.application.RefundsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The merchant's own refunds, newest first, chained by Next-Cursor; filters
 *  intersect (the account filter rides the owning intent); foreign cursors
 *  resolve to nothing. */
@AutoConfigureMockMvc
class RefundsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private RefundsService refunds;

  @Autowired
  private SimulatorService simulator;

  private String merchantKey;
  private UUID merchantId;
  private UUID accountA;
  private UUID accountB;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Refunds Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    accountA = accountsService.open(merchantId, new OpenAccountCommand("Refund Listing A")).publicId();
    accountB = accountsService.open(merchantId, new OpenAccountCommand("Refund Listing B")).publicId();
    // Account A: one settled intent with three REQUESTED refunds.
    var onA = createAndSettleIntent(accountA, "200.0000");
    for (int i = 0; i < 3; i++) {
      refunds.create(merchantId, onA.publicId(),
          new CreateRefundCommand(Money.ofBrl("25.0000"), Duration.ofMinutes(10)));
    }
    // Account B: one settled intent with one refund (filter asymmetry).
    var onB = createAndSettleIntent(accountB, "100.0000");
    refunds.create(merchantId, onB.publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
  }

  private PaymentIntent createAndSettleIntent(UUID accountPublicId, String amount) {
    var intent = payments.create(merchantId,
        new CreateIntentCommand(accountPublicId, Money.ofBrl(amount), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    return payments.get(merchantId, intent.publicId());
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/refunds" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        headers.add(cursor);
      }
    }
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryRefundExactlyOnceNewestFirst() throws Exception {
    var newest = refunds.create(merchantId, createAndSettleIntent(accountA, "50.0000").publicId(),
        new CreateRefundCommand(Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void statusAndAccountFiltersNarrowTheListing() throws Exception {
    mockMvc.perform(get("/v1/refunds?account=" + accountA)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
    mockMvc.perform(get("/v1/refunds?account=" + accountB)
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mockMvc.perform(get("/v1/refunds?status=SETTLED")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/refunds?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/refunds?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/refunds?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
```

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/RefundsListingRestApiTest.java
git commit -m "test: cover the refunds listing surface"
```

- [ ] **Step 3: Implement**

`RefundsRepository` — add:

```java
  List<Refund> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit);
```

`JdbcClientRefundsRepository` — add (same column list as its `findByPublicId`; account scope rides the intent join `OutstandingHolds` uses):

```java
  @Override
  public List<Refund> listByAccounts(List<UUID> accountPublicIds, String status,
      UUID account, UUID after, int limit) {
    return jdbc.sql("""
        select r.public_id, r.intent_public_id, r.amount, r.status, r.network_refund_public_id,
               r.expires_at, r.created_at, r.settled_at, r.hold_transaction_public_id,
               r.execute_transaction_public_id, r.return_transaction_public_id
        from payments.refund r
        join payments.payment_intent i on i.public_id = r.intent_public_id
        where i.account_public_id in (:accountPublicIds)
          and (:status::text is null or r.status = :status)
          and (:account::uuid is null or i.account_public_id = :account)
          and (:after::uuid is null
               or r.id < (select r2.id from payments.refund r2 where r2.public_id = :after))
        order by r.id desc
        limit :limit
        """)
        .param("accountPublicIds", accountPublicIds)
        .param("status", status)
        .param("account", account)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> mapRefund(rs))
        .list();
  }
```

`RefundsService` — add:

```java
  /** The merchant's refunds, newest first, keyset-paginated. */
  List<Refund> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit);
```

`RefundsServiceImpl` — add (inject `AccountsService accounts` if the class does not already hold it — add the constructor parameter and assignment if missing):

```java
  @Override
  @Transactional(readOnly = true)
  public List<Refund> list(UUID merchantPublicId, String status, UUID account, UUID after, int limit) {
    var accountIds = accounts.listPublicIds(merchantPublicId);
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return repository.listByAccounts(accountIds, status, account, after, limit);
  }
```

`RefundsController` — add (method-level mapping; this class has no class-level `@RequestMapping`), with imports `java.util.List` and `org.springframework.web.bind.annotation.RequestParam` (adjust `refunds` to the controller's actual injected field name):

```java
  @GetMapping("/v1/refunds")
  ResponseEntity<List<RefundResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID account,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned refund's public id) only when the probe found an extra row.
    var page = refunds.list(merchant.merchantPublicId(), status, account, after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(RefundResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(RefundResponse::from).toList());
  }
```

- [ ] **Step 4: Commit the implementation**

```bash
git add src/main/java/com/leandrossb/nummus/payments/application/RefundsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientRefundsRepository.java \
  src/main/java/com/leandrossb/nummus/payments/application/RefundsService.java \
  src/main/java/com/leandrossb/nummus/payments/application/RefundsServiceImpl.java \
  src/main/java/com/leandrossb/nummus/payments/interfaces/RefundsController.java
git commit -m "feat: list refunds with keyset pagination"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest=RefundsListingRestApiTest` on megalan. Expected: 4 tests PASS.

---

### Task 5: Bank-accounts listing gains the cursor

**Files:**
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/BankAccountsService.java`
- Modify: the `BankAccountsService` implementation class (locate it under `merchants/application` or `merchants/infrastructure`)
- Modify: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientBankAccountStore.java` and its store interface if the list method is declared in one
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/BankAccountsController.java`
- Test: `src/test/java/com/leandrossb/nummus/merchants/BankAccountsListingRestApiTest.java`

**Interfaces:**
- Consumes: `ApiDrivers.registerVerifiedBankAccount` (unique account numbers per call); existing `BankAccountResponse.from`.
- Produces: `GET /v1/bank-accounts?after=&limit=` → `200` JSON array + `Next-Cursor` (closes the standing backlog thread).

- [ ] **Step 1: Write the failing REST test**

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The merchant's registered destinations, newest first, chained by
 *  Next-Cursor; another merchant's registrations never surface. */
@AutoConfigureMockMvc
class BankAccountsListingRestApiTest extends IntegrationTestBase {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private BankAccountsService bankAccounts;

  private String merchantKey;
  private UUID merchantId;

  @BeforeEach
  void createFixtures() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bank Accounts Listing Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(JsonPath.read(body, "$.merchantId"));
    for (int i = 0; i < 4; i++) {
      ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    }
  }

  private List<String> walk(int limit) throws Exception {
    Set<String> headers = new HashSet<>();
    List<String> ids = new ArrayList<>();
    String query = "?limit=" + limit;
    int pages = 0;
    while (query != null) {
      MvcResult result = mockMvc.perform(get("/v1/bank-accounts" + query)
              .header("Authorization", "Bearer " + merchantKey))
          .andExpect(status().isOk()).andReturn();
      ids.addAll(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$[*].publicId"));
      String cursor = result.getResponse().getHeader("Next-Cursor");
      pages++;
      query = cursor == null ? null : "?limit=" + limit + "&after=" + cursor;
      if (cursor != null) {
        headers.add(cursor);
      }
    }
    assertEquals(pages - 1, headers.size());
    return ids;
  }

  @Test
  void cursorWalkCoversEveryAccountExactlyOnceNewestFirst() throws Exception {
    BankAccount newest = ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchantId);
    var ids = walk(2);
    assertEquals(5, ids.size());
    assertEquals(5, new HashSet<>(ids).size());
    assertEquals(newest.publicId().toString(), ids.get(0));
  }

  @Test
  void anotherMerchantsRegistrationsNeverSurface() throws Exception {
    // A second merchant's registration exists in the same pooled database.
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult other = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Bank Accounts Listing Other Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    UUID otherId = UUID.fromString(JsonPath.read(other.getResponse().getContentAsString(), "$.merchantId"));
    ApiDrivers.registerVerifiedBankAccount(bankAccounts, otherId);

    var ids = walk(3);
    assertEquals(4, ids.size());
  }

  @Test
  void unknownCursorYieldsAnEmptyPage() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts?after=" + UUID.randomUUID())
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void limitBoundsAreValidated() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts?limit=0")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/bank-accounts?limit=101")
            .header("Authorization", "Bearer " + merchantKey))
        .andExpect(status().isBadRequest());
  }
}
```

(If `BankAccount` lives at a different package than `merchants.domain`, adjust that one import to where `ApiDrivers.registerVerifiedBankAccount`'s return type declares it.)

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/merchants/BankAccountsListingRestApiTest.java
git commit -m "test: cover the bank-accounts listing cursor"
```

- [ ] **Step 3: Implement**

`JdbcClientBankAccountStore` — change `listByMerchant` to take the cursor and add the keyset clause (keep `COLUMNS` and `mapAccount` as they are; update the store interface declaration to the same signature if the method is declared in one):

```java
  public List<BankAccount> listByMerchant(UUID merchantPublicId, UUID after, int limit) {
    return jdbc.sql("select " + COLUMNS + " from merchants.bank_account"
        + " where merchant_public_id = :merchantPublicId"
        + " and (:after::uuid is null"
        + "     or id < (select b2.id from merchants.bank_account b2 where b2.public_id = :after))"
        + " order by id desc limit :limit")
        .param("merchantPublicId", merchantPublicId)
        .param("after", after)
        .param("limit", limit)
        .query((rs, i) -> mapAccount(rs))
        .list();
  }
```

`BankAccountsService` — change the signature:

```java
  /** The merchant's registered bank accounts, newest first, keyset-paginated. */
  List<BankAccount> list(UUID merchantPublicId, UUID after, int limit);
```

Implementation class — pass `after` through. Then grep for every caller of the old `list(merchantPublicId)` shape (`grep -rn "\.list(" src/main/java src/test/java | grep -i bankaccount`, plus the controller) and update each: production callers that need everything pass `null` cursor and an explicit limit (`100`); tests assert through the REST surface.

`BankAccountsController` — replace the list handler (keep its existing `@GetMapping` annotation as-is), with imports `java.util.List`, `org.springframework.http.ResponseEntity`, `org.springframework.web.bind.annotation.RequestParam` (adjust `bankAccounts` to the controller's actual injected field name):

```java
  ResponseEntity<List<BankAccountResponse>> list(AuthenticatedMerchant merchant,
      @RequestParam(required = false) UUID after,
      @RequestParam(defaultValue = "50") int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100: " + limit);
    }
    // Keyset pagination: fetch limit+1, hand back limit, and surface a Next-Cursor
    // (the last returned account's public id) only when the probe found an extra row.
    var page = bankAccounts.list(merchant.merchantPublicId(), after, limit + 1);
    if (page.size() <= limit) {
      return ResponseEntity.ok().body(page.stream().map(BankAccountResponse::from).toList());
    }
    var cursor = page.get(limit - 1).publicId();
    return ResponseEntity.ok().header("Next-Cursor", cursor.toString())
        .body(page.subList(0, limit).stream().map(BankAccountResponse::from).toList());
  }
```

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: paginate the bank-account listing by keyset"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='BankAccountsListingRestApiTest,BankAccountsRestApiTest'` on megalan (the second class pins the backward-compatible unparameterized read). Expected: all PASS.

---

### Task 6: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:** Consumes the shipped Tasks 1–5 surface. Produces documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table:
- **Instant payments** row: after "Payment intents and Pix-style dynamic charges: create, expire, settle;", insert "list with keyset pagination;" (keep the rest of the row unchanged).
- **Bank accounts** row: extend with ", listed with keyset pagination" at the end of the row (adjust punctuation to stay one sentence).

In the Status list, append after the M21 line:

```markdown
- [x] M22 — Merchant listings (keyset pagination)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M22 merchant listings complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify` via the Global Constraints command, minus `-Dtest`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
