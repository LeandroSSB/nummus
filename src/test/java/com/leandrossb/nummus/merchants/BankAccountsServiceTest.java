package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.BankAccountNotVerifiableException;
import com.leandrossb.nummus.merchants.application.BankAccountNotVerifiedException;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.DuplicateBankAccountException;
import com.leandrossb.nummus.merchants.application.InvalidVerificationCodeException;
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
    assertThrows(DuplicateBankAccountException.class,
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
