# M26 Velocity Limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `maxDailyIntentVolume` — a rolling-24h cap on the gross amount of intents a merchant attempts, operator-governed through the existing limits resource and enforced at intent creation before any network action.

**Architecture:** One storage migration (column on `merchants.merchant` + history column + `created_at` index), the `PaymentLimits` record gains a third nullable `BigDecimal`, every limits surface extends additively, and `PaymentsRepository.createdVolumeSince` sums the window scoped by the merchant's account ids.

**Tech Stack:** Java 25, Spring Boot, PostgreSQL/Flyway, JUnit 5, Testcontainers.

## Global Constraints

- English everywhere (code, comments, commits). Conventional Commits.
- Null cap = unlimited; comparisons inclusive (equal passes).
- Enforcement ordering: velocity check runs after the static `maxIntentAmount` check and before `network.createCharge` — an over-velocity request leaves no row (the M22 listing proves it).
- Window semantics: sum of `amount` over intents with `created_at >= now - 24h` scoped to the merchant's accounts; ALL statuses count (attempts are attempts — voided/expired included).
- No `Money` inside the merchants module ([[merchants-module-no-money]] — raw `BigDecimal`); payments wraps at its edge.
- Rejections never ride on writes; `PaymentVelocityExceededException` → 422 beside `PaymentLimitExceededException`.
- **Constructor ripple rule:** `PaymentLimits` gains a third component — every `new PaymentLimits(...)` in `src/main` and `src/test` gains the argument (grep both trees); in-memory `PaymentsRepository` fakes gain `createdVolumeSince`.
- **Verification protocol (no local Maven):** implementers edit, run no build, commit (test first, then implementation). The controller verifies remotely on megalan after each task (command as in prior milestones); include `ModuleBoundaryTest` when touching cross-module types.

---

### Task 1: V26, storage, and the limits surfaces extend

**Files:**
- Create: `src/main/resources/db/migration/V26__velocity_limits.sql`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimits.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/application/PaymentLimitsEntry.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientMerchantStore.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/UpdateLimitsRequest.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsResponse.java`
- Modify: `src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/LimitsHistoryResponse.java`
- Modify: every `new PaymentLimits(` site (constructor ripple rule)
- Test: `src/test/java/com/leandrossb/nummus/merchants/VelocityLimitsStoreRestApiTest.java`

**Interfaces:**
- Consumes: the M23 limits surfaces as they exist on main.
- Produces (Task 2 relies on): `PaymentLimits(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount, BigDecimal maxDailyIntentVolume)` with `unlimited()` passing three nulls; store history rows carrying the third column; `GET/PUT /v1/merchants/{id}/limits` and `/{id}/limits-history` carrying `maxDailyIntentVolume` (null = unlimited).

- [ ] **Step 1: Write the failing test**

In the idiom of `PaymentLimitsStoreTest` + `PaymentLimitsRestApiTest` (read both; operator-key fixture, merchant creation), one class covering:
1. store/service round-trip: `updatePaymentLimits(merchantId, new PaymentLimits(new BigDecimal("5000"), null, new BigDecimal("1000")), operatorKey)` → `findPaymentLimits` returns the three components; history entry `[0]` carries `maxDailyIntentVolume` 1000 with attribution; a null-clearing PUT (only static fields) resets the daily to null and appends a second entry carrying null.
2. REST: PUT `{"maxIntentAmount":5000.0000,"maxDailyIntentVolume":1000.0000}` → 200 with both fields; GET returns them; history `GET` newest entry carries `maxDailyIntentVolume` 1000.0000.
3. validation: PUT `{"maxDailyIntentVolume":-5}` → 400; unknown merchant PUT → 404.

Write the full file in those suites' idiom (`assertEquals`/assertThrows/MockMvc), including their `@AfterAll` conventions if any.

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/merchants/VelocityLimitsStoreRestApiTest.java
git commit -m "test: cover the daily volume limit round trip"
```

- [ ] **Step 3: Implement**

`V26__velocity_limits.sql`:

```sql
-- M26 velocity limits: a rolling-24h cap on the gross amount of intents a
-- merchant attempts. Same storage shape as M23: cached current value on the
-- merchant row, the history table gains the column additively (NULL = the
-- knob was unset at that change). The index serves the enforcement window
-- query (created_at range; account scoping rides the existing account index).

alter table merchants.merchant
  add column max_daily_intent_volume numeric(19,4)
    check (max_daily_intent_volume > 0);

alter table merchants.payment_limits_entry
  add column max_daily_intent_volume numeric(19,4);

create index payment_intent_created_at_idx
  on payments.payment_intent (created_at);
```

`PaymentLimits` — third component + javadoc line (null = unlimited; rolling 24h attempts):

```java
public record PaymentLimits(BigDecimal maxIntentAmount, BigDecimal maxPayoutAmount,
    BigDecimal maxDailyIntentVolume) {

  public static PaymentLimits unlimited() {
    return new PaymentLimits(null, null, null);
  }
}
```

`PaymentLimitsEntry` — gains `BigDecimal maxDailyIntentVolume` (position: after `maxPayoutAmount`).

`JdbcClientMerchantStore` — `findPaymentLimits` selects and returns the third column; `updatePaymentLimits` sets it; `insertPaymentLimitsEntry` binds it; `listPaymentLimitsHistory` selects `e.max_daily_intent_volume` and maps it into the entry record. All four edits mirror their existing two-column logic exactly.

DTOs — `UpdateLimitsRequest` gains the field with the same validation annotations; its `limits()` passes it through; `LimitsResponse` and `LimitsHistoryResponse` gain the field and map it (null-safe passthrough as their existing fields).

Ripple: update every `new PaymentLimits(` site (grep `src/main src/test`) — M23's tests pass the third `null` or explicit values as their assertions require; `FakeMerchantsService`'s `unlimited()` needs no change beyond the record.

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java src/main/resources
git commit -m "feat: store the daily intent volume limit"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='VelocityLimitsStoreRestApiTest,PaymentLimitsStoreTest,PaymentLimitsRestApiTest,ModuleBoundaryTest'` on megalan (the M23 suites pin the ripple). Expected: PASS.

---

### Task 2: Rolling-window enforcement at intent creation

**Files:**
- Create: `src/main/java/com/leandrossb/nummus/payments/domain/PaymentVelocityExceededException.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPaymentsRepository.java`
- Modify: `src/main/java/com/leandrossb/nummus/payments/application/PaymentsServiceImpl.java`
- Modify: `src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java`
- Modify: in-memory `PaymentsRepository` fakes (ripple rule)
- Test: `src/test/java/com/leandrossb/nummus/payments/VelocityEnforcementRestApiTest.java`

**Interfaces:**
- Consumes: Task 1's `PaymentLimits.maxDailyIntentVolume()`; `AccountsService.listPublicIds`.
- Produces: `PaymentsRepository.createdVolumeSince(List<UUID> accountPublicIds, Instant from) → Money`; `PaymentVelocityExceededException(UUID merchantPublicId, Money windowUsage, Money requested, Money cap)` → 422.

- [ ] **Step 1: Write the failing test**

In the `PaymentLimitsEnforcementTest` idiom (read it; operator fixture, `setLimits` helper extended to take the daily volume), one class covering:
1. cap 1 000: create intents of 600 then 390 (REST, `201`) → a 20 intent rejects 422 with the problem detail containing "1000" (cap); the M22 listing shows exactly 2 intents (no row for the rejected one); a 10 intent passes (inclusive 600+390+10 = 1 000).
2. window rolls: backdate the 600 intent's `created_at` to `now() - interval '25 hours'` (adminConnection, the suite idiom) → a 400 intent passes (390 + 400 ≤ 1 000).
3. voided attempts still count: void one created intent (the M25 endpoint) → the window usage is unchanged (the next over-cap attempt still rejects with the same numbers).
4. unset = unlimited: no daily cap set → a 1 000 000 intent passes.
5. compose: static 500 + daily 1 000 → a 600 intent rejects on static (422 static wording); a 400 then 700 sequence: 400 passes, 700 exceeds daily (400+700 > 1 000) → 422 velocity.

Write the full file in that suite's idiom.

- [ ] **Step 2: Commit the failing test**

```bash
git add src/test/java/com/leandrossb/nummus/payments/VelocityEnforcementRestApiTest.java
git commit -m "test: cover rolling daily volume enforcement"
```

- [ ] **Step 3: Implement**

`PaymentsRepository` — add:

```java
  /** Gross amount of intents created at or after the instant, across the
   *  given accounts — the velocity window's usage (attempts of any status). */
  Money createdVolumeSince(List<UUID> accountPublicIds, Instant from);
```

`JdbcClientPaymentsRepository`:

```java
  @Override
  public Money createdVolumeSince(List<UUID> accountPublicIds, Instant from) {
    var sum = jdbc.sql("""
        select coalesce(sum(amount), 0) from payments.payment_intent
        where account_public_id in (:accountPublicIds) and created_at >= :from
        """)
        .param("accountPublicIds", accountPublicIds)
        .param("from", toOffsetDateTime(from))
        .query((rs, i) -> rs.getBigDecimal(1))
        .single();
    return Money.of(sum, BRL);
  }
```

(the class's existing `BRL` constant; empty ids cannot reach here — the caller checks first).

`PaymentVelocityExceededException` — mirror `PaymentLimitExceededException`'s shape:

```java
public class PaymentVelocityExceededException extends RuntimeException {
  // (merchantPublicId, windowUsage, requested, cap) with a message:
  // "daily intent volume exceeded for merchant <id>: window <usage> BRL +
  //  requested <req> BRL > cap <cap> BRL"
}
```

`GlobalExceptionHandler` — `@ExceptionHandler(PaymentVelocityExceededException.class)` → `UNPROCESSABLE_ENTITY`, beside `paymentLimitExceeded`.

`PaymentsServiceImpl.create` — directly AFTER the static cap block and BEFORE `network.createCharge`:

```java
    // Velocity: the rolling 24h attempt volume plus this request must stay
    // within the daily cap. Every status counts — attempts are attempts.
    // Unset is unlimited; the comparison is inclusive.
    var daily = limits.maxDailyIntentVolume();
    if (daily != null) {
      var accountIds = accounts.listPublicIds(merchantPublicId);
      if (!accountIds.isEmpty()) {
        var windowUsage = repository.createdVolumeSince(accountIds,
            Instant.now().minus(Duration.ofHours(24)));
        var capAsMoney = Money.of(daily, cmd.amount().currency());
        if (windowUsage.add(cmd.amount()).compareTo(capAsMoney) > 0) {
          throw new PaymentVelocityExceededException(merchantPublicId, windowUsage,
              cmd.amount(), capAsMoney);
        }
      }
    }
```

(wrapping the BigDecimal cap into the command's currency at the edge — the merchants module's raw-BigDecimal rule; `Duration`/`Money` already imported).

In-memory fakes gain `createdVolumeSince` mirroring their storage (sum over their intent store filtered by account membership and instant).

- [ ] **Step 4: Commit the implementation**

```bash
git add -A src/main/java src/test/java
git commit -m "feat: enforce the rolling daily intent volume at creation"
```

- [ ] **Step 5: Controller verifies**

`./mvnw -B test -Dtest='VelocityEnforcementRestApiTest,ModuleBoundaryTest'` on megalan. Expected: PASS.

---

### Task 3: README — capability and status entries

**Files:**
- Modify: `README.md`

**Interfaces:** Consumes Tasks 1–2. Produces documentation only.

- [ ] **Step 1: Update the two README sections**

In the Capabilities table, **Instant payments** row: replace "per-merchant amount caps," with "per-merchant amount and rolling-daily volume caps," (keep the rest unchanged).

In the Status list, append after the M25 line:

```markdown
- [x] M26 — Velocity limits (rolling daily volume)
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: mark M26 velocity limits complete"
```

- [ ] **Step 3: Controller verifies**

Full suite on megalan (`./mvnw -B verify`). Expected: BUILD SUCCESS, Failures: 0, Errors: 0.
