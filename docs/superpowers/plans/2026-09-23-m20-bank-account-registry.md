# M20 — Bank-Account Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Payout destinations become registered, verified, merchant-scoped bank accounts — structured Brazilian fields, one-time-code verification, and payouts that reference the account and derive the wire key.

**Architecture:** The registry lands inside `merchants` as a vertical slice (schema → domain → store → service), then its REST surface and error mappings, then the payout integration swaps the request field from a raw key to a bank-account id and stores the reference on the payout row. The `merchants.application` port is the only new seam `payments` touches — the exact `FeeSchedule` edge.

**Tech Stack:** Java 25, Spring Boot (`@Transactional`, `JdbcClient`), PostgreSQL + Flyway (V22 registry schema, V23 payout column), JUnit 5 + Testcontainers, MockMvc, `ApiDrivers` fixtures.

**Spec:** `docs/superpowers/specs/2026-09-23-m20-bank-account-registry-design.md`

## Global Constraints

- **English everywhere**; Conventional Commits; every commit message ends with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- **Public repository read as a real product** — no portfolio/demo framing anywhere.
- **No local Maven/JVM runs, ever.** All builds/tests run on the megalan CI container. Focused run (branch `worktree-m20-bank-accounts`, replace `<Tests>`):
  ```
  ssh megalan 'cd ~/nummus-ci && git fetch -q origin && git checkout -q -B worktree-m20-bank-accounts origin/worktree-m20-bank-accounts && docker run --rm -v $HOME/nummus-ci:/src -w /src -v /var/run/docker.sock:/var/run/docker.sock -v nummus-m2:/root/.m2 -e TESTCONTAINERS_HOST_OVERRIDE=172.17.0.1 -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock maven:3.9-eclipse-temurin-25 ./mvnw -B test -Dtest=<Tests>'
  ```
  Full verify = same with `./mvnw -B verify`. Every task: **push first**, then run remotely. Retry a timed-out ssh once. Remote runs take ~2-5 min; full verify ≈ 5 min.
- **TDD strictly:** failing tests (remote RED) first as a `test:` commit; implementation lands as the task's `feat:` commit; both carry the trailer.
- **No new dependencies.** Baseline: **446 tests, all green** (on merged main `b9eb735`). Running totals are provisional — the authoritative total is 446 + your cumulative `@Test` count; report the true number.
- **Worktree:** execution starts from a worktree on branch `worktree-m20-bank-accounts`. Never commit on `main` (docs commits excepted, per house precedent).
- Standing idioms: `ApiDrivers`, loopback URLs, `"Bearer " + secret`, jsonPath reads, membership pins, `@AfterAll` sweeps carried with every copied recipe (bank accounts carry no windowed timestamps — no sweep entries), status-guarded transitions with re-read, shown-once secrets (SHA-256 at rest).
- Money is `Money.ofBrl(...)`/`Money.of(BigDecimal, BRL)`; comparisons via `compareTo`; never `double`.
- Cross-merchant access is indistinguishable from unknown — every read/guard keys on `(merchant_public_id, public_id)`.
- ArchUnit: merchants stays self-contained; business modules touch only `merchants.application` — no `merchants.domain`/`infrastructure`/`interfaces` imports outside the module. `payments` already imports `merchants.application` (legal edge).

## File Map (final state after all tasks)

```
src/main/resources/db/migration/V22__bank_accounts.sql (new, T1)
src/main/resources/db/migration/V23__payout_bank_account.sql (new, T3)
src/main/java/com/leandrossb/nummus/merchants/domain/BankAccount.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/domain/RegisterBankAccountCommand.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/TaxIds.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/BankAccountStore.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/BankAccountsService.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/BankAccountsServiceImpl.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/IssuedBankAccount.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/PayoutDestination.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/UnknownBankAccountException.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/BankAccountNotVerifiedException.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/BankAccountNotVerifiableException.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/application/InvalidVerificationCodeException.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/infrastructure/JdbcClientBankAccountStore.java (new, T1)
src/main/java/com/leandrossb/nummus/merchants/interfaces/BankAccountsController.java (new, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/RegisterBankAccountRequest.java (new, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/BankAccountResponse.java (new, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/IssuedBankAccountResponse.java (new, T2)
src/main/java/com/leandrossb/nummus/merchants/interfaces/dto/VerifyBankAccountRequest.java (new, T2)
src/main/java/com/leandrossb/nummus/interfaces/GlobalExceptionHandler.java (modify, T2 — 3 group adds + one 422 handler)
src/main/java/com/leandrossb/nummus/interfaces/auth/MerchantAuthFilter.java (modify, T2 — MERCHANT_ROUTES)
src/main/java/com/leandrossb/nummus/payments/domain/CreatePayoutCommand.java (modify, T3 — bankAccountPublicId)
src/main/java/com/leandrossb/nummus/payments/domain/Payout.java (modify, T3 — +bankAccountPublicId)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsService.java (unchanged)
src/main/java/com/leandrossb/nummus/payments/application/PayoutsServiceImpl.java (modify, T3)
src/main/java/com/leandrossb/nummus/payments/infrastructure/JdbcClientPayoutsRepository.java (modify, T3)
src/main/java/com/leandrossb/nummus/payments/interfaces/PayoutsController.java (modify, T3)
src/main/java/com/leandrossb/nummus/payments/interfaces/dto/CreatePayoutRequest.java (modify, T3)
src/main/java/com/leandrossb/nummus/payments/interfaces/dto/PayoutResponse.java (modify, T3)
src/test/java/com/leandrossb/nummus/merchants/application/FakeBankAccountsService.java (new, T3)
src/test/java/com/leandrossb/nummus/testutils/ApiDrivers.java (modify, T3 — registerVerifiedBankAccount)
src/test/java/com/leandrossb/nummus/merchants/TaxIdsTest.java (new, T1)
src/test/java/com/leandrossb/nummus/merchants/BankAccountsServiceTest.java (new, T1)
src/test/java/com/leandrossb/nummus/merchants/BankAccountsSchemaTest.java (new, T1)
src/test/java/com/leandrossb/nummus/merchants/BankAccountsRestApiTest.java (new, T2)
src/test/java/com/leandrossb/nummus/payments/PayoutsRestApiTest.java (modify, T3 — re-drive + new pins)
src/test/java/com/leandrossb/nummus/payments/PayoutRequestTest.java (modify, T3 — re-drive)
src/test/java/com/leandrossb/nummus/payments/PayoutLifecycleTest.java (modify, T3 — re-drive)
src/test/java/com/leandrossb/nummus/payments/PayoutSchemaTest.java (modify, T3 — column pin)
src/test/java/com/leandrossb/nummus/payments/application/PayoutsServiceImplTest.java (modify, T3 — fake wiring)
src/test/java/com/leandrossb/nummus/payments/application/InMemoryPayoutsRepository.java (modify, T3 — record field)
src/test/java/com/leandrossb/nummus/conciliation/MoneyOutSettlementQueryTest.java (modify, T3 — re-drive)
src/test/java/com/leandrossb/nummus/conciliation/ConciliationRestApiTest.java (modify, T3 — re-drive)
README.md, docs/m2-backlog.md (modify, T4)
```

(Migration-rename lesson applied: V23 adds a nullable column to `payments.payout` — grep `src/test` for payout column lists touching that table before finalizing T3's file list; `PayoutSchemaTest` is the known pin.)

---

### Task 1: V22 + the registry core

**Files:**
- Create: `V22__bank_accounts.sql`, `BankAccount.java`, `RegisterBankAccountCommand.java`, `TaxIds.java`, `BankAccountStore.java`, `BankAccountsService.java`, `BankAccountsServiceImpl.java`, `IssuedBankAccount.java`, `PayoutDestination.java`, the four exceptions, `JdbcClientBankAccountStore.java`
- Test: `TaxIdsTest.java` (unit), `BankAccountsServiceTest.java`, `BankAccountsSchemaTest.java` (integration, `IntegrationTestBase`)

**Interfaces:**
- Produces (T2/T3 consume): `BankAccountsService.register(UUID merchantPublicId, RegisterBankAccountCommand)` → `IssuedBankAccount(BankAccount account, String verificationCode)`; `find(UUID, UUID)` → `Optional<BankAccount>`; `list(UUID)` → `List<BankAccount>`; `verify(UUID, UUID, String rawCode)` → `BankAccount`; `revoke(UUID, UUID)`; `requireVerifiedDestination(UUID, UUID)` → `PayoutDestination(UUID bankAccountPublicId, String wireKey)`. Exceptions (all `merchants.application`): `UnknownBankAccountException(UUID)` → 404, `BankAccountNotVerifiedException(UUID, String status)` → 422, `BankAccountNotVerifiableException(UUID, String status)` → 409, `InvalidVerificationCodeException(UUID)` → 401 — HTTP mapping lands in T2, the types exist here.

- [ ] **Step 1: Write the failing tests** — three new suites:

`src/test/java/com/leandrossb/nummus/merchants/TaxIdsTest.java`:

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.merchants.application.TaxIds;
import org.junit.jupiter.api.Test;

class TaxIdsTest {

  @Test
  void acceptsKnownGoodCpfAndCnpj() {
    assertDoesNotThrow(() -> TaxIds.requireValidTaxId("11144477735"));
    assertDoesNotThrow(() -> TaxIds.requireValidTaxId("11222333000181"));
  }

  @Test
  void rejectsWrongCheckDigitsOnBothKinds() {
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11144477736"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11222333000182"));
  }

  @Test
  void rejectsWrongLengths() {
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("1114447773"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("111444777355555"));
    assertThrows(NullPointerException.class, () -> TaxIds.requireValidTaxId(null));
  }

  @Test
  void rejectsRepeatedDigitPatterns() {
    // All-same-digit tax ids pass the arithmetic but are never real documents.
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11111111111"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("00000000000000"));
  }
}
```

`src/test/java/com/leandrossb/nummus/merchants/BankAccountsServiceTest.java`:

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.BankAccountNotVerifiableException;
import com.leandrossb.nummus.merchants.application.BankAccountNotVerifiedException;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.InvalidVerificationCodeException;
import com.leandrossb.nummus.merchants.application.IssuedBankAccount;
import com.leandrossb.nummus.merchants.application.UnknownBankAccountException;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BankAccountsServiceTest extends IntegrationTestBase {

  @Autowired
  private BankAccountsService bankAccounts;

  private RegisterBankAccountCommand command(String accountNumber) {
    return new RegisterBankAccountCommand("123", "4567", accountNumber, "11144477735");
  }

  @Test
  void registerStartsPendingAndVerifiesByCode() {
    var issued = bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89101-2"));

    assertEquals("PENDING_VERIFICATION", issued.account().status());
    assertTrue(issued.verificationCode().startsWith("nummus_bac_"));

    var verified = bankAccounts.verify(SeedMerchant.PUBLIC_ID, issued.account().publicId(),
        issued.verificationCode());
    assertEquals("VERIFIED", verified.status());
    assertTrue(verified.verifiedAt() != null);

    // The code is single-use by state: a second verify hits the terminal guard.
    assertThrows(BankAccountNotVerifiableException.class, () -> bankAccounts
        .verify(SeedMerchant.PUBLIC_ID, issued.account().publicId(), issued.verificationCode()));
  }

  @Test
  void wrongCodeIsRejectedWithoutMutating() {
    var issued = bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89102-3"));

    assertThrows(InvalidVerificationCodeException.class, () -> bankAccounts
        .verify(SeedMerchant.PUBLIC_ID, issued.account().publicId(), "nummus_bac_wrong"));
    assertEquals("PENDING_VERIFICATION",
        bankAccounts.find(SeedMerchant.PUBLIC_ID, issued.account().publicId()).orElseThrow()
            .status());
  }

  @Test
  void checkDigitFailuresRejectAtRegistration() {
    assertThrows(IllegalArgumentException.class,
        () -> bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89103-4").withTaxId("11144477736")));
  }

  @Test
  void duplicateActiveRegistrationIsRejectedAndRevocationFreesTheKey() {
    var issued = bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89104-5"));
    assertThrows(Exception.class,
        () -> bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89104-5")));

    bankAccounts.revoke(SeedMerchant.PUBLIC_ID, issued.account().publicId());
    assertEquals("REVOKED",
        bankAccounts.find(SeedMerchant.PUBLIC_ID, issued.account().publicId()).orElseThrow()
            .status());
    // The natural key is free again once the prior registration is revoked.
    var reissued = bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89104-5"));
    assertNotEquals(issued.account().publicId(), reissued.account().publicId());
  }

  @Test
  void destinationsRequireVerificationAndDeriveTheWireKey() {
    var pending = bankAccounts.register(SeedMerchant.PUBLIC_ID, command("89105-6"));
    assertThrows(BankAccountNotVerifiedException.class, () -> bankAccounts
        .requireVerifiedDestination(SeedMerchant.PUBLIC_ID, pending.account().publicId()));

    bankAccounts.verify(SeedMerchant.PUBLIC_ID, pending.account().publicId(),
        pending.verificationCode());
    var destination = bankAccounts.requireVerifiedDestination(SeedMerchant.PUBLIC_ID,
        pending.account().publicId());
    assertEquals("123-4567-89105-6", destination.wireKey());
    assertEquals(pending.account().publicId(), destination.bankAccountPublicId());

    assertThrows(UnknownBankAccountException.class,
        () -> bankAccounts.requireVerifiedDestination(SeedMerchant.PUBLIC_ID, UUID.randomUUID()));
    // A foreign merchant's account is indistinguishable from unknown.
    assertThrows(UnknownBankAccountException.class, () -> bankAccounts
        .requireVerifiedDestination(UUID.randomUUID(), pending.account().publicId()));
  }
}
```

  (`withTaxId` is a convenience on `RegisterBankAccountCommand` — add it in Step 3: `public RegisterBankAccountCommand withTaxId(String tax) { return new RegisterBankAccountCommand(bankCode, branch, accountNumber, tax); }`. Drop it and inline a full constructor at that one call site if you prefer.)

`src/test/java/com/leandrossb/nummus/merchants/BankAccountsSchemaTest.java`:

```java
package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BankAccountsSchemaTest extends IntegrationTestBase {

  @Test
  void schemaEnforcesShapeChecksAndThePartialUnique() throws Exception {
    try (var c = adminConnection(); var st = c.createStatement()) {
      String merchant = SeedMerchant.PUBLIC_ID.toString();
      String insert = "INSERT INTO merchants.bank_account (merchant_public_id, bank_code, branch,"
          + " account_number, holder_tax_id, verification_code_hash)"
          + " VALUES ('" + merchant + "', '%s', '4567', '99999-9', '11144477735', 'hash')";
      SQLException badBankCode = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("12")));
      assertEquals("23514", badBankCode.getSQLState());
      SQLException badTaxId = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("123").replace("'11144477735'", "1114447773")));
      assertEquals("23514", badTaxId.getSQLState());

      st.executeUpdate(insert.formatted("123"));
      SQLException duplicateActive = assertThrows(SQLException.class,
          () -> st.executeUpdate(insert.formatted("123")));
      assertEquals("23505", duplicateActive.getSQLState());
      st.executeUpdate("UPDATE merchants.bank_account SET status = 'REVOKED'"
          + " WHERE merchant_public_id = '" + merchant + "' AND account_number = '99999-9'");
      st.executeUpdate(insert.formatted("123"));
      SQLException badStatus = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE merchants.bank_account SET status = 'WEIRD'"
              + " WHERE merchant_public_id = '" + merchant + "'"
              + " AND account_number = '99999-9'"));
      assertEquals("23514", badStatus.getSQLState());
    }
  }

  @Test
  void appRoleHasNoDeleteAndNoFullRowUpdate() throws Exception {
    // Column-scoped update: the app role cannot rewrite the code hash.
    try (var app = appConnection(); var st = app.createStatement()) {
      SQLException denied = assertThrows(SQLException.class, () -> st.executeUpdate(
          "UPDATE merchants.bank_account SET verification_code_hash = 'x'"
              + " WHERE merchant_public_id = '" + SeedMerchant.PUBLIC_ID + "'"));
      assertEquals("42501", denied.getSQLState());
    }
  }
}
```

  (`appConnection()` — check `IntegrationTestBase` for an existing app-role helper (`ConciliationRolesTest`/`AttributionSchemaTest` own one); if it is private to those classes, copy the pattern into the base or this test, whatever the house precedent does — grep before inventing.)

- [ ] **Step 2: Remote RED** — push; FAIL (compile: the new types absent).

- [ ] **Step 3: Implement** — in order:

  1. **`V22__bank_accounts.sql`:**

```sql
-- M20 bank-account registry: payout destinations become registered,
-- merchant-scoped, verified bank accounts. The table stores the structured
-- Brazilian payout shape plus a SHA-256 verification-code hash (the code is
-- shown once at registration, the API-key precedent). The partial unique
-- keeps one active registration per natural key per merchant; revoked rows
-- free the key. Mutations are status-guarded transitions only — the app
-- role updates just the two status columns, never the identity fields.

create table merchants.bank_account (
  id                    bigint generated always as identity primary key,
  public_id             uuid not null default gen_random_uuid() unique,
  merchant_public_id    uuid not null,
  bank_code             text not null check (bank_code ~ '^\d{3}$'),
  branch                text not null check (branch ~ '^\d{1,5}(-\d)?$'),
  account_number        text not null check (account_number ~ '^[0-9-]{1,20}$'),
  holder_tax_id         text not null check (holder_tax_id ~ '^\d{11}$|^\d{14}$'),
  status                text not null default 'PENDING_VERIFICATION'
                        check (status in ('PENDING_VERIFICATION','VERIFIED','REVOKED')),
  verification_code_hash text not null,
  created_at            timestamptz not null default now(),
  verified_at           timestamptz
);

create unique index bank_account_active_natural_key_uq
  on merchants.bank_account (merchant_public_id, bank_code, branch, account_number)
  where status <> 'REVOKED';
create index bank_account_merchant_idx on merchants.bank_account (merchant_public_id);

grant select, insert on merchants.bank_account to nummus_app;
grant update (status, verified_at) on merchants.bank_account to nummus_app;
```

  2. **`merchants/domain/BankAccount.java`:**

```java
package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A merchant's registered payout destination — the structured Brazilian bank
 * account. {@code status} is PENDING_VERIFICATION until the one-time code is
 * redeemed, then VERIFIED; REVOKED is the soft-delete terminal. The wire key
 * payouts derive ({@code bank-branch-account}) is never stored as identity —
 * the structured fields are the truth.
 */
public record BankAccount(UUID publicId, UUID merchantPublicId, String bankCode, String branch,
    String accountNumber, String holderTaxId, String status, Instant createdAt,
    Instant verifiedAt) {
}
```

  3. **`merchants/domain/RegisterBankAccountCommand.java`:**

```java
package com.leandrossb.nummus.merchants.domain;

/** Command to register a payout destination. Shapes are validated at the
 *  REST boundary; the tax-id check digits are validated in the service. */
public record RegisterBankAccountCommand(String bankCode, String branch, String accountNumber,
    String holderTaxId) {

  public RegisterBankAccountCommand withTaxId(String taxId) {
    return new RegisterBankAccountCommand(bankCode, branch, accountNumber, taxId);
  }
}
```

  4. **`merchants/application/TaxIds.java`:**

```java
package com.leandrossb.nummus.merchants.application;

import java.util.Objects;

/** CPF/CNPJ check-digit arithmetic — shape says well-formed, never real. */
public final class TaxIds {

  private TaxIds() {
  }

  /** @throws IllegalArgumentException wrong length or failing check digits. */
  public static void requireValidTaxId(String taxId) {
    Objects.requireNonNull(taxId, "holderTaxId must not be null");
    if (taxId.length() == 11) {
      requireDocument(taxId, new int[] {10, 9, 8, 7, 6, 5, 4, 3, 2},
          new int[] {11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
    } else if (taxId.length() == 14) {
      requireDocument(taxId, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2},
          new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
    } else {
      throw new IllegalArgumentException(
          "holderTaxId must be 11 (CPF) or 14 (CNPJ) digits: " + taxId.length());
    }
  }

  private static void requireDocument(String taxId, int[] firstWeights, int[] secondWeights) {
    if (taxId.chars().distinct().count() == 1) {
      throw new IllegalArgumentException(
          "holderTaxId must not be a repeated-digit document: " + taxId);
    }
    int length = taxId.length();
    if (digitAt(taxId, length - 2) != checkDigit(taxId, firstWeights)
        || digitAt(taxId, length - 1) != checkDigit(taxId, secondWeights)) {
      throw new IllegalArgumentException("holderTaxId check digits do not match: " + taxId);
    }
  }

  private static int checkDigit(String taxId, int[] weights) {
    int sum = 0;
    for (int i = 0; i < weights.length; i++) {
      sum += digitAt(taxId, i) * weights[i];
    }
    int rest = sum % 11;
    return rest < 2 ? 0 : 11 - rest;
  }

  private static int digitAt(String taxId, int index) {
    return taxId.charAt(index) - '0';
  }
}
```

  5. **The four exceptions** (constructor shapes mirror `UnknownApiKeyException` — message carries the id/status):

```java
public final class UnknownBankAccountException extends RuntimeException {
  public UnknownBankAccountException(UUID publicId) {
    super("bank account not found: " + publicId);
  }
}
```

```java
public final class BankAccountNotVerifiedException extends RuntimeException {
  public BankAccountNotVerifiedException(UUID publicId, String status) {
    super("bank account is not verified: " + publicId + " (status: " + status + ")");
  }
}
```

```java
public final class BankAccountNotVerifiableException extends RuntimeException {
  public BankAccountNotVerifiableException(UUID publicId, String status) {
    super("bank account is not pending verification: " + publicId + " (status: " + status + ")");
  }
}
```

```java
public final class InvalidVerificationCodeException extends RuntimeException {
  public InvalidVerificationCodeException(UUID publicId) {
    super("verification code rejected: " + publicId);
  }
}
```

  6. **`merchants/application/BankAccountStore.java`** (port):

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the bank-account registry. Transitions are
 *  status-guarded; identity fields are write-once. */
public interface BankAccountStore {

  BankAccount insert(BankAccount account, String verificationCodeHash);

  Optional<BankAccount> findByPublicIdAndMerchant(UUID merchantPublicId, UUID publicId);

  List<BankAccount> listByMerchant(UUID merchantPublicId, int limit);

  /** Verifies in one guarded statement: wins only while the row is
   *  PENDING_VERIFICATION and the hash matches. */
  boolean verify(UUID merchantPublicId, UUID publicId, String verificationCodeHash);

  /** Revokes in one guarded statement: wins from PENDING_VERIFICATION or
   *  VERIFIED. */
  boolean revoke(UUID merchantPublicId, UUID publicId);
}
```

  7. **`merchants/infrastructure/JdbcClientBankAccountStore.java`** — the `JdbcClientMerchantStore` pattern with the guarded updates of `JdbcClientPayoutsRepository`:

```java
package com.leandrossb.nummus.merchants.infrastructure;

import com.leandrossb.nummus.merchants.application.BankAccountStore;
import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientBankAccountStore implements BankAccountStore {

  private static final String COLUMNS = """
      public_id, merchant_public_id, bank_code, branch, account_number, holder_tax_id,
      status, created_at, verified_at
      """;

  private final JdbcClient jdbc;

  public JdbcClientBankAccountStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public BankAccount insert(BankAccount account, String verificationCodeHash) {
    jdbc.sql("""
        insert into merchants.bank_account
          (public_id, merchant_public_id, bank_code, branch, account_number, holder_tax_id,
           status, verification_code_hash, created_at)
        values (:publicId, :merchantPublicId, :bankCode, :branch, :accountNumber, :holderTaxId,
                :status, :codeHash, :createdAt)
        """)
        .param("publicId", account.publicId())
        .param("merchantPublicId", account.merchantPublicId())
        .param("bankCode", account.bankCode())
        .param("branch", account.branch())
        .param("accountNumber", account.accountNumber())
        .param("holderTaxId", account.holderTaxId())
        .param("status", account.status())
        .param("codeHash", verificationCodeHash)
        .param("createdAt", toOffsetDateTime(account.createdAt()))
        .update();
    return account;
  }

  @Override
  public Optional<BankAccount> findByPublicIdAndMerchant(UUID merchantPublicId, UUID publicId) {
    return jdbc.sql("select " + COLUMNS + " from merchants.bank_account"
        + " where public_id = :publicId and merchant_public_id = :merchantPublicId")
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .query((rs, i) -> mapAccount(rs))
        .optional();
  }

  @Override
  public List<BankAccount> listByMerchant(UUID merchantPublicId, int limit) {
    return jdbc.sql("select " + COLUMNS + " from merchants.bank_account"
        + " where merchant_public_id = :merchantPublicId order by id desc limit :limit")
        .param("merchantPublicId", merchantPublicId)
        .param("limit", limit)
        .query((rs, i) -> mapAccount(rs))
        .list();
  }

  @Override
  public boolean verify(UUID merchantPublicId, UUID publicId, String verificationCodeHash) {
    int updated = jdbc.sql("""
        update merchants.bank_account
        set status = 'VERIFIED', verified_at = now()
        where public_id = :publicId and merchant_public_id = :merchantPublicId
          and status = 'PENDING_VERIFICATION' and verification_code_hash = :codeHash
        """)
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .param("codeHash", verificationCodeHash)
        .update();
    return updated == 1;
  }

  @Override
  public boolean revoke(UUID merchantPublicId, UUID publicId) {
    int updated = jdbc.sql("""
        update merchants.bank_account
        set status = 'REVOKED'
        where public_id = :publicId and merchant_public_id = :merchantPublicId
          and status in ('PENDING_VERIFICATION', 'VERIFIED')
        """)
        .param("publicId", publicId)
        .param("merchantPublicId", merchantPublicId)
        .update();
    return updated == 1;
  }

  private BankAccount mapAccount(ResultSet rs) throws SQLException {
    OffsetDateTime verifiedAt = rs.getObject("verified_at", OffsetDateTime.class);
    return new BankAccount(rs.getObject("public_id", UUID.class),
        rs.getObject("merchant_public_id", UUID.class), rs.getString("bank_code"),
        rs.getString("branch"), rs.getString("account_number"), rs.getString("holder_tax_id"),
        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        verifiedAt == null ? null : verifiedAt.toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
```

  8. **`merchants/application/IssuedBankAccount.java`** and **`PayoutDestination.java`**:

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;

/** A freshly registered account: the ONLY time the verification code is visible. */
public record IssuedBankAccount(BankAccount account, String verificationCode) {
}
```

```java
package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** What a payout needs from a verified destination: the account it references
 *  and the wire key derived from the structured fields. The registry's other
 *  data (tax id, hashes) never crosses this port. */
public record PayoutDestination(UUID bankAccountPublicId, String wireKey) {
}
```

  9. **`merchants/application/BankAccountsService.java`** (port):

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The bank-account registry: a merchant's registered payout destinations. */
public interface BankAccountsService {

  /** Registers a destination (PENDING_VERIFICATION) and returns the one-time
   *  verification code. Tax-id check digits are validated here — the REST
   *  layer checks shapes only. */
  IssuedBankAccount register(UUID merchantPublicId, RegisterBankAccountCommand cmd);

  /** The merchant's account; empty for unknown or foreign ids. */
  Optional<BankAccount> find(UUID merchantPublicId, UUID publicId);

  /** The merchant's accounts, most recent first (bounded). */
  List<BankAccount> list(UUID merchantPublicId);

  /** Redeems the one-time code: PENDING_VERIFICATION → VERIFIED. */
  BankAccount verify(UUID merchantPublicId, UUID publicId, String rawCode);

  /** Soft-deletes: → REVOKED (terminal). */
  void revoke(UUID merchantPublicId, UUID publicId);

  /** Resolves a payout destination: the account must exist for this merchant
   *  and be VERIFIED. @throws UnknownBankAccountException unknown or foreign
   *  id; @throws BankAccountNotVerifiedException not yet verified (or revoked). */
  PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId);
}
```

  10. **`merchants/application/BankAccountsServiceImpl.java`**:

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registry lifecycle. Codes are 256-bit, base64url, prefixed, shown once —
 *  the API-key precedent; hashes share the same SHA-256 helper. */
@Service
public class BankAccountsServiceImpl implements BankAccountsService {

  private static final String CODE_PREFIX = "nummus_bac_";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final BankAccountStore store;

  public BankAccountsServiceImpl(BankAccountStore store) {
    this.store = store;
  }

  @Override
  @Transactional
  public IssuedBankAccount register(UUID merchantPublicId, RegisterBankAccountCommand cmd) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    Objects.requireNonNull(cmd, "command must not be null");
    TaxIds.requireValidTaxId(cmd.holderTaxId());
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String rawCode = CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    var account = new BankAccount(UUID.randomUUID(), merchantPublicId, cmd.bankCode(),
        cmd.branch(), cmd.accountNumber(), cmd.holderTaxId(), "PENDING_VERIFICATION",
        Instant.now(), null);
    store.insert(account, MerchantsServiceImpl.sha256Hex(rawCode));
    // Read back what persistence decided (defaults, truncations never — but
    // the returned row is what every later read will match on).
    var stored = store.findByPublicIdAndMerchant(merchantPublicId, account.publicId())
        .orElseThrow(() -> new IllegalStateException("bank account row missing after insert"));
    return new IssuedBankAccount(stored, rawCode);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BankAccount> find(UUID merchantPublicId, UUID publicId) {
    return store.findByPublicIdAndMerchant(merchantPublicId, publicId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<BankAccount> list(UUID merchantPublicId) {
    return store.listByMerchant(merchantPublicId, 50);
  }

  @Override
  @Transactional
  public BankAccount verify(UUID merchantPublicId, UUID publicId, String rawCode) {
    Objects.requireNonNull(rawCode, "code must not be null");
    if (!store.verify(merchantPublicId, publicId, MerchantsServiceImpl.sha256Hex(rawCode))) {
      var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
          .orElseThrow(() -> new UnknownBankAccountException(publicId));
      if (!"PENDING_VERIFICATION".equals(account.status())) {
        throw new BankAccountNotVerifiableException(publicId, account.status());
      }
      throw new InvalidVerificationCodeException(publicId);
    }
    return store.findByPublicIdAndMerchant(merchantPublicId, publicId)
        .orElseThrow(() -> new UnknownBankAccountException(publicId));
  }

  @Override
  @Transactional
  public void revoke(UUID merchantPublicId, UUID publicId) {
    if (!store.revoke(merchantPublicId, publicId)) {
      var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
          .orElseThrow(() -> new UnknownBankAccountException(publicId));
      throw new BankAccountNotVerifiableException(publicId, account.status());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId) {
    var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
        .orElseThrow(() -> new UnknownBankAccountException(publicId));
    if (!"VERIFIED".equals(account.status())) {
      throw new BankAccountNotVerifiedException(publicId, account.status());
    }
    return new PayoutDestination(account.publicId(),
        account.bankCode() + "-" + account.branch() + "-" + account.accountNumber());
  }
}
```

  (`MerchantsServiceImpl.sha256Hex` is package-private static — same package, callable. If its visibility blocks you, promote it to `public` and note it in the report.)

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='TaxIdsTest,BankAccountsServiceTest,BankAccountsSchemaTest,MerchantsRestApiTest,MerchantStoreTest'` → green; full verify → BUILD SUCCESS, **457 tests** (446 + 11, provisional: 4 unit + 5 service + 2 schema).

- [ ] **Step 5: Commit** — `feat: add the merchant bank-account registry (V22)` + trailer; push.

---

### Task 2: The registry REST surface

**Files:**
- Create: `BankAccountsController.java`, `dto/RegisterBankAccountRequest.java`, `dto/BankAccountResponse.java`, `dto/IssuedBankAccountResponse.java`, `dto/VerifyBankAccountRequest.java`
- Modify: `GlobalExceptionHandler.java` (three group adds + one 422 handler), `MerchantAuthFilter.java` (MERCHANT_ROUTES), plus any test pinning the route list (grep first)
- Test: `BankAccountsRestApiTest.java`

**Interfaces:**
- Consumes: T1's `BankAccountsService` and the four exception types.
- Produces: routes `POST /v1/bank-accounts` (201 + code shown once), `GET /v1/bank-accounts` (list), `GET /v1/bank-accounts/{id}`, `POST /v1/bank-accounts/{id}/verify` (200), `DELETE /v1/bank-accounts/{id}` (204); merchant-auth gated. Exception HTTP mapping: 404 / 422 / 409 / 401 as pinned by T1's javadocs.

- [ ] **Step 1: Write the failing tests** — `src/test/java/com/leandrossb/nummus/merchants/BankAccountsRestApiTest.java`:

```java
package com.leandrossb.nummus.merchants;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class BankAccountsRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  private final String merchantAuth = "Bearer "
      + ApiDrivers.createMerchantAndGetKey(mockMvc, ApiDrivers.operatorAuth(operatorKeys),
          "Bank Account Merchant");

  private String body(String bankCode, String branch, String account, String taxId) {
    return "{\"bankCode\":\"" + bankCode + "\",\"branch\":\"" + branch
        + "\",\"accountNumber\":\"" + account + "\",\"holderTaxId\":\"" + taxId + "\"}";
  }

  private MvcResult register(String accountNumber) throws Exception {
    return mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("123", "4567", accountNumber, "11144477735")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
        .andExpect(jsonPath("$.bankCode").value("123"))
        .andExpect(jsonPath("$.holderTaxId").value("11144477735"))
        .andReturn();
  }

  @Test
  void registrationShowsTheCodeOnceAndNeverAgain() throws Exception {
    var created = register("77101-2");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");
    String code = JsonPath.read(created.getResponse().getContentAsString(), "$.verificationCode");

    mockMvc.perform(get("/v1/bank-accounts/" + account).header("Authorization", merchantAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verificationCode").doesNotExist())
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
    mockMvc.perform(get("/v1/bank-accounts").header("Authorization", merchantAuth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.bankAccountId == '" + account + "')].status")
            .value("PENDING_VERIFICATION"));
  }

  @Test
  void verifyRedeemsTheCodeAndRejectsReplayAndWrongCode() throws Exception {
    var created = register("77201-3");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");
    String code = JsonPath.read(created.getResponse().getContentAsString(), "$.verificationCode");

    mockMvc.perform(post("/v1/bank-accounts/" + account + "/verify")
            .header("Authorization", merchantAuth)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VERIFIED"))
        .andExpect(jsonPath("$.verifiedAt").exists());

    mockMvc.perform(post("/v1/bank-accounts/" + account + "/verify")
            .header("Authorization", merchantAuth)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + code + "\"}"))
        .andExpect(status().isConflict());

    mockMvc.perform(post("/v1/bank-accounts/" + UUID.randomUUID() + "/verify")
            .header("Authorization", merchantAuth)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"nummus_bac_anything\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void shapeValidationMapsTo400() throws Exception {
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("12", "4567", "89101-2", "11144477735")))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/v1/bank-accounts")
            .header("Authorization", merchantAuth).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("123", "4567", "89101-2", "11144477736")))
        .andExpect(status().isBadRequest()); // check digits — service-level 400
  }

  @Test
  void revokeIsTerminalAndScoped() throws Exception {
    var created = register("77301-4");
    String account = JsonPath.read(created.getResponse().getContentAsString(), "$.bankAccountId");

    mockMvc.perform(delete("/v1/bank-accounts/" + account).header("Authorization", merchantAuth))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/v1/bank-accounts/" + account).header("Authorization", merchantAuth))
        .andExpect(status().isConflict());

    // A foreign merchant's account is indistinguishable from unknown.
    String other = "Bearer " + ApiDrivers.createMerchantAndGetKey(mockMvc,
        ApiDrivers.operatorAuth(operatorKeys), "Other Bank Merchant");
    mockMvc.perform(get("/v1/bank-accounts/" + account).header("Authorization", other))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/v1/bank-accounts").header("Authorization", other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.bankAccountId == '" + account + "')]").doesNotExist());
  }

  @Test
  void routesRequireMerchantAuth() throws Exception {
    mockMvc.perform(get("/v1/bank-accounts")).andExpect(status().isUnauthorized());
  }
}
```

  (If `OperatorKeysService` needs an import: `com.leandrossb.nummus.merchants.application.OperatorKeysService`. `merchantAuth` as an instance field initialized inline is the `ConciliationRestApiTest` lazy pattern adapted — use a lazy getter if the inline field ordering fights the autowired `mockMvc`/`operatorKeys` nulling during construction; the house lazy-getter precedent is `ConciliationRestApiTest.operatorAuth()`.)

- [ ] **Step 2: Remote RED** — push; FAIL (routes absent — 404/401 mismatches).

- [ ] **Step 3: Implement** — in order:

  1. **DTOs:**

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for registering a payout destination. Shapes only — the
 *  tax-id check digits are validated in the service (400 either way). */
public record RegisterBankAccountRequest(
    @NotBlank(message = "bankCode must not be blank")
    @Pattern(regexp = "\\d{3}", message = "bankCode must be exactly 3 digits") String bankCode,
    @NotBlank(message = "branch must not be blank")
    @Pattern(regexp = "\\d{1,5}(-\\d)?",
        message = "branch must be 1-5 digits with an optional check digit") String branch,
    @NotBlank(message = "accountNumber must not be blank")
    @Pattern(regexp = "[0-9-]{1,20}",
        message = "accountNumber must be 1-20 digits or dashes") String accountNumber,
    @NotBlank(message = "holderTaxId must not be blank")
    @Pattern(regexp = "\\d{11}|\\d{14}",
        message = "holderTaxId must be 11 (CPF) or 14 (CNPJ) digits") String holderTaxId) {
}
```

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.time.Instant;
import java.util.UUID;

/** REST view of a registered account. The verification code appears ONLY on
 *  the creation response — never here. */
public record BankAccountResponse(
    UUID bankAccountId, String bankCode, String branch, String accountNumber,
    String holderTaxId, String status, Instant createdAt, Instant verifiedAt) {

  public static BankAccountResponse from(BankAccount account) {
    return new BankAccountResponse(account.publicId(), account.bankCode(), account.branch(),
        account.accountNumber(), account.holderTaxId(), account.status(), account.createdAt(),
        account.verifiedAt());
  }
}
```

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.IssuedBankAccount;

/** Creation responses carry the verification code exactly once. */
public record IssuedBankAccountResponse(
    UUID bankAccountId, String bankCode, String branch, String accountNumber,
    String holderTaxId, String status, Instant createdAt, String verificationCode) {

  public static IssuedBankAccountResponse from(IssuedBankAccount issued) {
    var account = issued.account();
    return new IssuedBankAccountResponse(account.publicId(), account.bankCode(),
        account.branch(), account.accountNumber(), account.holderTaxId(), account.status(),
        account.createdAt(), issued.verificationCode());
  }
}
```

```java
package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyBankAccountRequest(
    @NotBlank(message = "code must not be blank") String code) {
}
```

  2. **`merchants/interfaces/BankAccountsController.java`** (the `MeController` shape):

```java
package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.UnknownBankAccountException;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import com.leandrossb.nummus.merchants.interfaces.dto.BankAccountResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.IssuedBankAccountResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.RegisterBankAccountRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.VerifyBankAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-serve registry surface: the authenticated merchant manages its own
 *  payout destinations. */
@RestController
@RequestMapping("/v1/bank-accounts")
class BankAccountsController {

  private final BankAccountsService bankAccounts;

  BankAccountsController(BankAccountsService bankAccounts) {
    this.bankAccounts = bankAccounts;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<IssuedBankAccountResponse> register(AuthenticatedMerchant merchant,
      @Valid @RequestBody RegisterBankAccountRequest request) {
    var issued = bankAccounts.register(merchant.merchantPublicId(),
        new RegisterBankAccountCommand(request.bankCode(), request.branch(),
            request.accountNumber(), request.holderTaxId()));
    return ResponseEntity
        .created(URI.create("/v1/bank-accounts/" + issued.account().publicId()))
        .body(IssuedBankAccountResponse.from(issued));
  }

  @GetMapping
  List<BankAccountResponse> list(AuthenticatedMerchant merchant) {
    return bankAccounts.list(merchant.merchantPublicId()).stream()
        .map(BankAccountResponse::from).toList();
  }

  @GetMapping("/{id}")
  BankAccountResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return bankAccounts.find(merchant.merchantPublicId(), id)
        .map(BankAccountResponse::from)
        .orElseThrow(() -> new UnknownBankAccountException(id));
  }

  @Idempotent
  @PostMapping("/{id}/verify")
  BankAccountResponse verify(AuthenticatedMerchant merchant, @PathVariable UUID id,
      @Valid @RequestBody VerifyBankAccountRequest request) {
    return BankAccountResponse.from(
        bankAccounts.verify(merchant.merchantPublicId(), id, request.code()));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> revoke(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    bankAccounts.revoke(merchant.merchantPublicId(), id);
    return ResponseEntity.noContent().build();
  }
}
```

  3. **`GlobalExceptionHandler.java`** — imports from `merchants.application`; four edits:
     - the `notFound` group (the one with `UnknownApiKeyException`) gains `UnknownBankAccountException.class`
     - the `conflict` group (with `PaymentAccountNotActiveException`) gains `BankAccountNotVerifiableException.class`
     - the `unauthorized` group (`MerchantUnauthorizedException`) gains `InvalidVerificationCodeException.class`
     - a new handler beside `insufficientFunds`:

```java
  @ExceptionHandler(BankAccountNotVerifiedException.class)
  ProblemDetail bankAccountNotVerified(BankAccountNotVerifiedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }
```

  4. **`MerchantAuthFilter.java`** — `MERCHANT_ROUTES` gains `"/v1/bank-accounts"` (after `"/v1/accounts"` reads naturally). Grep `src/test` for route-list pins (`MerchantAuthFilterTest`, `MerchantScopingTest`, `OperatorGatingTest`) and extend them with the new route if they enumerate lists.

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='BankAccountsRestApiTest,MerchantAuthFilterTest,MerchantScopingTest,OperatorGatingTest,MerchantsRestApiTest'` → green; full verify → BUILD SUCCESS, **462 tests** (457 + 5, provisional).

- [ ] **Step 5: Commit** — `feat: expose the bank-account registry over REST` + trailer; push.

---

### Task 3: Payout integration — registry-only destinations

**Files:**
- Create: `V23__payout_bank_account.sql`, `FakeBankAccountsService.java` (test, `merchants.application` package)
- Modify: `CreatePayoutCommand.java`, `Payout.java`, `PayoutsServiceImpl.java`, `JdbcClientPayoutsRepository.java`, `PayoutsController.java`, `CreatePayoutRequest.java`, `PayoutResponse.java`, `ApiDrivers.java`, and the re-drive list: `PayoutsRestApiTest`, `PayoutRequestTest`, `PayoutLifecycleTest`, `PayoutSchemaTest`, `PayoutsServiceImplTest`, `InMemoryPayoutsRepository`, `MoneyOutSettlementQueryTest`, `ConciliationRestApiTest`
- Test: new pins in `PayoutsRestApiTest` (+3 methods); everything else re-driven

**Interfaces:**
- Consumes: T1's `BankAccountsService.requireVerifiedDestination` → `PayoutDestination(UUID bankAccountPublicId, String wireKey)`.
- Produces: `CreatePayoutCommand(UUID accountPublicId, Money amount, UUID bankAccountPublicId, Duration ttl)`; REST request field `bankAccountId`; `Payout` gains `bankAccountPublicId` (after `destinationBankKey`); `PayoutResponse` gains `bankAccountId`; `ApiDrivers.registerVerifiedBankAccount(BankAccountsService, UUID merchantPublicId)` → `BankAccount` (unique account number per call).

- [ ] **Step 1: Write the failing tests** — extend `PayoutsRestApiTest` with three methods (reuse its existing fixture style — grep the class for how it funds accounts and posts payouts):

```java
  @Test
  void payoutRequiresAVerifiedBankAccount() throws Exception {
    var fixture = fundedMerchant("payout-verified-guard"); // existing funding helper pattern
    var issued = bankAccounts.register(fixture.merchantPublicId(),
        new RegisterBankAccountCommand("123", "4567", "55101-2", "11144477735"));

    // PENDING_VERIFICATION → 422
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", fixture.auth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + fixture.accountId() + "\",\"amount\":\"10.0000\","
                + "\"bankAccountId\":\"" + issued.account().publicId() + "\"}"))
        .andExpect(status().isUnprocessableEntity());

    // Verified → the derived key travels
    bankAccounts.verify(fixture.merchantPublicId(), issued.account().publicId(),
        issued.verificationCode());
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", fixture.auth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + fixture.accountId() + "\",\"amount\":\"10.0000\","
                + "\"bankAccountId\":\"" + issued.account().publicId() + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.destinationBankKey").value("123-4567-55101-2"))
        .andExpect(jsonPath("$.bankAccountId").value(issued.account().publicId().toString()));

    // Foreign merchant's account → indistinguishable from unknown
    String other = ApiDrivers.createMerchantAndGetKey(mockMvc,
        ApiDrivers.operatorAuth(operatorKeys), "Foreign Registry Merchant");
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", "Bearer " + other).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + fixture.accountId() + "\",\"amount\":\"10.0000\","
                + "\"bankAccountId\":\"" + issued.account().publicId() + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void revokedBankAccountBlocksNewPayoutsOnly() throws Exception {
    var fixture = fundedMerchant("payout-revoked-guard");
    var account = ApiDrivers.registerVerifiedBankAccount(bankAccounts, fixture.merchantPublicId());
    bankAccounts.revoke(fixture.merchantPublicId(), account.publicId());

    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", fixture.auth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + fixture.accountId() + "\",\"amount\":\"10.0000\","
                + "\"bankAccountId\":\"" + account.publicId() + "\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void rawDestinationKeyIsGone() throws Exception {
    var fixture = fundedMerchant("payout-raw-key-gone");
    mockMvc.perform(post("/v1/payouts")
            .header("Authorization", fixture.auth()).header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accountId\":\"" + fixture.accountId() + "\",\"amount\":\"10.0000\","
                + "\"destinationBankKey\":\"bank.raw\"}"))
        .andExpect(status().isBadRequest());
  }
```

  (`fundedMerchant` = the class's existing funded-account helper under whatever name it has — grep and reuse; add a `BankAccountsService bankAccounts` autowire plus `OperatorKeysService` if absent. Adapt helper names to the file's reality; the assertions are the contract.) Also re-drive in the same commit: every existing payout fixture in this class switches its JSON to `bankAccountId` from `ApiDrivers.registerVerifiedBankAccount(...).publicId()`.

- [ ] **Step 2: Remote RED** — push; FAIL (compile: command field absent; new tests fail).

- [ ] **Step 3: Implement** — in order:

  1. **`V23__payout_bank_account.sql`:**

```sql
-- M20 payout integration: the payout row references the registered bank
-- account it paid into. Nullable by design — pre-M20 rows paid raw keys and
-- keep a null reference; the wire key column still carries the derived value
-- for every new row.

alter table payments.payout add column bank_account_public_id uuid;
```

  2. **`CreatePayoutCommand.java`** — third component becomes the reference:

```java
/** Command to request a payout. A null ttl selects the default expiry window.
 *  The destination is a registered, verified bank account — the wire key is
 *  derived at request time. */
public record CreatePayoutCommand(UUID accountPublicId, Money amount,
    UUID bankAccountPublicId, Duration ttl) {
}
```

  3. **`CreatePayoutRequest.java`** — `destinationBankKey` replaced:

```java
    @NotNull(message = "bankAccountId must not be null") UUID bankAccountId,
```

  (keep `accountId`, `amount`, `expiresInSeconds` and their annotations exactly as they are; drop the now-unused `NotBlank`/`Pattern`/`Size` imports only if nothing else in the file uses them).

  4. **`PayoutsController.create`** — the command wiring:

```java
    var payout = payouts.create(merchant.merchantPublicId(), new CreatePayoutCommand(
        request.accountId(),
        Money.of(request.amount(), BRL),
        request.bankAccountId(),
        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds())));
```

  5. **`Payout.java`** — gains the reference (after `destinationBankKey`): add `UUID bankAccountPublicId,` between `destinationBankKey` and `transferPublicId`, with one javadoc line: the registered account this payout paid into; null on pre-M20 rows.

  6. **`PayoutsServiceImpl`** — field + constructor param `BankAccountsService bankAccounts` (import `com.leandrossb.nummus.merchants.application.BankAccountsService`); in `create`, immediately after the ttl validation and BEFORE `accounts.get`:

```java
    // Reference data resolves before any money is held: an unknown or
    // unverified destination must never take the ledger lock.
    var destination =
        bankAccounts.requireVerifiedDestination(merchantPublicId, cmd.bankAccountPublicId());
```

  and the two uses of the raw key become `destination.wireKey()`, with the insert gaining `destination.bankAccountPublicId()` in the new component's position:

```java
    var transfer = network.createPayoutTransfer(cmd.amount(), destination.wireKey());
```

```java
    return repository.insert(new Payout(payoutId, account.publicId(), cmd.amount(),
        PayoutStatus.REQUESTED, destination.wireKey(), destination.bankAccountPublicId(),
        transfer.publicId(), Instant.now().plus(ttl), Instant.now(), null, null,
        reservation.publicId(), null, null));
```

  7. **`JdbcClientPayoutsRepository`** — `insert` gains the column+param (`:bankAccountId` after `destination_bank_key`), both selects and `findSettledBetween` gain `bank_account_public_id` to the column lists, and `mapPayout` maps it (`rs.getObject("bank_account_public_id", UUID.class)` in the record's new position).

  8. **`PayoutResponse`** — gains `UUID bankAccountId` after `destinationBankKey`; `from` maps `payout.bankAccountPublicId()`.

  9. **`FakeBankAccountsService`** (test tree, `src/test/java/com/leandrossb/nummus/merchants/application/`) — the `FakeMerchantsService` pattern:

```java
package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake arming verified destinations for service-level payout
 *  tests — the FakeMerchantsService precedent. */
public class FakeBankAccountsService implements BankAccountsService {

  private final Map<UUID, String> wireKeys = new ConcurrentHashMap<>();

  /** Arms a destination as VERIFIED with an exact wire key. */
  public void armVerified(UUID bankAccountPublicId, String wireKey) {
    wireKeys.put(bankAccountPublicId, wireKey);
  }

  @Override
  public PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId) {
    String wireKey = wireKeys.get(publicId);
    if (wireKey == null) {
      throw new UnknownBankAccountException(publicId);
    }
    return new PayoutDestination(publicId, wireKey);
  }

  @Override public IssuedBankAccount register(UUID m, RegisterBankAccountCommand c) {
    throw new UnsupportedOperationException("not needed at this altitude");
  }

  @Override public Optional<BankAccount> find(UUID m, UUID id) {
    return Optional.empty();
  }

  @Override public List<BankAccount> list(UUID m) {
    return List.of();
  }

  @Override public BankAccount verify(UUID m, UUID id, String code) {
    throw new UnsupportedOperationException("not needed at this altitude");
  }

  @Override public void revoke(UUID m, UUID id) {
    wireKeys.remove(id);
  }
}
```

  10. **`ApiDrivers`** — gains the shared fixture (unique account number per call — the natural-key unique makes shared numbers collide within a merchant):

```java
  private static final java.util.concurrent.atomic.AtomicLong BANK_ACCOUNT_SEQ =
      new java.util.concurrent.atomic.AtomicLong();

  /** Registers and verifies a payout destination for the merchant, returning
   *  the stored account — every call a unique account number, so fixtures
   *  never collide on the natural-key unique. The wire key is
   *  {@code 123-4567-<accountNumber>}. */
  public static com.leandrossb.nummus.merchants.domain.BankAccount registerVerifiedBankAccount(
      com.leandrossb.nummus.merchants.application.BankAccountsService bankAccounts,
      UUID merchantPublicId) {
    String accountNumber = String.format("100%06d", BANK_ACCOUNT_SEQ.incrementAndGet());
    var issued = bankAccounts.register(merchantPublicId, new com.leandrossb.nummus.merchants
        .domain.RegisterBankAccountCommand("123", "4567", accountNumber, "11144477735"));
    bankAccounts.verify(merchantPublicId, issued.account().publicId(), issued.verificationCode());
    return issued.account();
  }
```

  (Use normal imports rather than fully-qualified names — shown qualified here only to keep this plan block copy-safe.)

  11. **Re-drive the suites** — mechanical, one shape everywhere: `new CreatePayoutCommand(accountId, amount, bankAccountId, ttl)` where `bankAccountId` comes from `ApiDrivers.registerVerifiedBankAccount(bankAccounts, merchant).publicId()` (add the `BankAccountsService` autowire where absent):
      - `PayoutRequestTest` (~12 sites, lines ~122–338): replace the `"bank.*"` literals; the two SQL asserts on `destination_bank_key` (lines ~170, ~232) assert the helper's derived key instead (`"123-4567-" + accountNumber` — have the helper's return value at hand).
      - `PayoutLifecycleTest` (~1 site, line ~99; the event-payload pin at ~140 becomes the derived key `"123-4567-100000NNN"` — read it from the payout's own `destinationBankKey()` rather than hardcoding).
      - `PayoutsServiceImplTest` (line ~70): wire `FakeBankAccountsService` into the `PayoutsServiceImpl` constructor and `armVerified(id, "bank.cancel-lost-01")` before the create (keep the fake's wire key literal if the test asserts on it — grep first).
      - `MoneyOutSettlementQueryTest` + `ConciliationRestApiTest` (`settlePayout`-style helpers, 3 sites): helper gains the register+verify line.
      - `PayoutsRestApiTest`: JSON bodies swap `destinationBankKey` for `bankAccountId`; the `$.destinationBankKey` echo pin (line ~145) asserts the derived key; the shape-validation test (line ~231) drops its `destinationBankKey` case and gains a missing-`bankAccountId` 400 case.
      - `PayoutSchemaTest`: extend the payout-row pin with `bank_account_public_id` (nullable, null default).

- [ ] **Step 4: Remote GREEN + verify** — focused `-Dtest='PayoutsRestApiTest,PayoutRequestTest,PayoutLifecycleTest,PayoutSchemaTest,PayoutsServiceImplTest,PayoutFeeScheduleTest,MoneyOutSettlementQueryTest,ConciliationRestApiTest,BankAccountsServiceTest'` (grep `merchants/PayoutFeeScheduleTest` too — it builds payouts via fees; include if it compiles against the command) → green; full verify → BUILD SUCCESS, **465 tests** (462 + 3, provisional).

- [ ] **Step 5: Commit** — `feat: resolve payouts through the bank-account registry (V23)` + trailer; push.

---

### Task 4: Final verification and milestone bookkeeping

**Files:**
- Modify: `README.md` — capability table gains a row after **Conciliation**:

```markdown
| **Bank accounts** | Merchant-scoped registry of payout destinations: structured Brazilian bank accounts with check-digit validation, one-time-code verification, and registry-only payouts that derive the wire key |
```

  Status gains `- [x] M20 — Bank-account registry`.
- Modify: `docs/m2-backlog.md` — append at the end of the file:

```markdown
## From the M20 design

M20 closed the M16 destination bound: payout destinations are registered,
merchant-scoped, verified bank accounts — structured Brazilian fields with
check-digit validation, a one-time-code verification cycle (shown once,
hashed at rest), and registry-only payouts that derive the wire key from
the registered fields. Known bounds, deliberate:

- **No webhook events and no audit entries** for registration or
  verification — the event catalog stays lifecycle-only and the audit log
  stays operator-writes-only (the M15 stance).
- **The listing is unpaginated** (50 most recent) — the standing backlog
  thread.
- **Check digits validate shape, not existence** — well-formed CPF/CNPJ
  arithmetic and field shapes; no external verification exists in the
  simulator era.
- **No default destination** — payouts always name the account.
- **Pre-M20 payouts keep raw keys** with a null bank-account reference;
  revocation blocks new payouts only — in-flight payouts captured their
  key at creation, so nothing strands.
- **Same destination across merchants** derives the same wire key —
  transfers do not scope by merchant; each registration carries its own
  verification cycle.
```

- [ ] **Step 1: Remote full verify** — `Tests run: <N>, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (N = 446 + cumulative; per-task totals are provisional).
- [ ] **Step 2+3:** README/backlog edits per the text above.
- [ ] **Step 4: Commit** — `docs: mark M20 bank-account registry complete` + trailer; push.
- [ ] **Step 5: Report** the remote verify summary line verbatim.

---

## Spec coverage map

| Spec section | Tasks |
| --- | --- |
| Entity + field shapes + CPF/CNPJ check digits | 1 |
| Two-step verification (code shown once, guarded, 401/409) | 1, 2 |
| REST surface + merchant scoping (404 isolation) | 2 |
| V22 schema (checks, partial unique, column-scoped grants) | 1 |
| Registry-only payouts (bankAccountId, derived wire key, 422/404, V23) | 3 |
| PayoutResponse bankAccountId (additive) | 3 |
| In-flight payouts unaffected by revocation | 3 (revoked-blocks-new pin) |
| README + backlog + final gate | 4 |

Spec deviation, recorded: the spec's rollout note implies one migration; the payout reference column lands as a separate `V23` in the task that first uses it (T3) so no schema ships dead. V22 covers the registry exactly as the spec describes it.
