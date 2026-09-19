package com.leandrossb.nummus.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.application.AccountsServiceImpl;
import com.leandrossb.nummus.accounts.application.InMemoryAccountsRepository;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.InMemoryLedgerRepository;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.LedgerServiceImpl;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.FakeMerchantsService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentsServiceImplTest {

  private final InMemoryLedgerRepository ledgerRepository = new InMemoryLedgerRepository();
  private final Ledger ledger = new LedgerServiceImpl(ledgerRepository);
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository());
  private final FakePaymentNetwork network = new FakePaymentNetwork();
  private final InMemoryPaymentsRepository repo = new InMemoryPaymentsRepository();
  private final MerchantsService merchants = new FakeMerchantsService();
  // Outbox publishing is covered by WebhookPublishTest; unit scope ignores events.
  private final PaymentsService payments =
      new PaymentsServiceImpl(ledger, accounts, network, repo, event -> { }, merchants);

  PaymentsServiceImplTest() {
    // Test-scope composition layer: mirror the V5 clearing-asset seed so the fixed
    // clearing UUID exists before settlement posts against it.
    ledgerRepository.insertAccount(new LedgerAccount(PaymentClearingAccount.PUBLIC_ID,
        "psp clearing", AccountType.ASSET, Currency.getInstance("BRL"),
        AccountStatus.ACTIVE, Instant.now(), null));
  }

  @Test
  void createOpensChargeAndStoresCreatedIntent() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    assertNotNull(intent.publicId());
    assertEquals(IntentStatus.CREATED, intent.status());
    assertNotNull(intent.chargePublicId());
    assertNotNull(intent.expiresAt());
    assertEquals(ChargeStatus.PENDING, network.getCharge(intent.chargePublicId()).status());
  }

  @Test
  void createValidatesTtlBoundsAndAccount() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    assertThrows(IllegalArgumentException.class, () -> payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), Duration.ofSeconds(59))));
    assertThrows(IllegalArgumentException.class, () -> payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), Duration.ofSeconds(86401))));
    assertThrows(com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException.class, () ->
        payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(UUID.randomUUID(), Money.ofBrl("1.0000"), null)));
    assertThrows(NullPointerException.class, () -> payments.create(SeedMerchant.PUBLIC_ID, null));
  }

  @Test
  void frozenAccountRejectsCreation() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    accounts.freeze(SeedMerchant.PUBLIC_ID, account.publicId());
    assertThrows(com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException.class, () ->
        payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("1.0000"), null)));
  }

  @Test
  void succeededChargeSettlesExactlyOnceWithBalancedEntry() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), null));
    network.succeed(intent.chargePublicId());

    var settled = payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    assertEquals(IntentStatus.SETTLED, settled.status());
    assertNotNull(settled.journalTransactionPublicId());
    assertNotNull(settled.settledAt());

    // clearing debited, merchant payable credited (raw DR-CR view of the ledger)
    assertEquals(0, ledger.balance(PaymentClearingAccount.PUBLIC_ID)
        .compareTo(Money.ofBrl("10.0000")));
    var again = payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    assertEquals(IntentStatus.SETTLED, again.status());
    assertEquals(settled.journalTransactionPublicId(), again.journalTransactionPublicId());
  }

  @Test
  void failedChargeFailsTheIntent() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.fail(intent.chargePublicId());
    assertEquals(IntentStatus.FAILED, payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()).status());
  }

  @Test
  void expiredIntentRefusesSettlementEvenAfterSuccess() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    repo.agePastExpiry(intent.publicId());
    network.succeed(intent.chargePublicId());
    assertEquals(IntentStatus.EXPIRED, payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()).status());
  }

  @Test
  void amountMismatchIsAnInvariantBreach() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.succeed(intent.chargePublicId());
    network.mutateAmount(intent.chargePublicId(), Money.ofBrl("6.0000"));
    assertThrows(ChargeAmountMismatchException.class, () -> payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()));
  }

  @Test
  void frozenAtSettleTimeLeavesIntentCreatedAndSettlesAfterUnfreeze() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID, new CreateIntentCommand(account.publicId(), Money.ofBrl("5.0000"), null));
    network.succeed(intent.chargePublicId());
    accounts.freeze(SeedMerchant.PUBLIC_ID, account.publicId());

    assertThrows(com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException.class,
        () -> payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()));
    assertEquals(IntentStatus.CREATED, repo.findByPublicId(intent.publicId()).orElseThrow().status());

    accounts.unfreeze(SeedMerchant.PUBLIC_ID, account.publicId());
    assertEquals(IntentStatus.SETTLED, payments.get(SeedMerchant.PUBLIC_ID, intent.publicId()).status());
  }

  @Test
  void unknownIntentThrows() {
    assertThrows(UnknownPaymentIntentException.class,
        () -> payments.get(SeedMerchant.PUBLIC_ID, UUID.randomUUID()));
  }
}
