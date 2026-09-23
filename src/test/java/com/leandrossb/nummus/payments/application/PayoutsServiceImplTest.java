package com.leandrossb.nummus.payments.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import com.leandrossb.nummus.merchants.application.FakeBankAccountsService;
import com.leandrossb.nummus.merchants.application.FakeMerchantsService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.PayoutStatus;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Focused service-level pins for {@code PayoutsServiceImpl.get}'s expiry
 * resolution that the simulator cannot drive deterministically: the
 * cancel-vs-pay interleave is emulated through the test-only boundary double
 * {@link FakePaymentNetwork} (the {@code PaymentsServiceImplTest} precedent —
 * production paths never touch the fake).
 */
class PayoutsServiceImplTest {

  private final InMemoryLedgerRepository ledgerRepository = new InMemoryLedgerRepository();
  private final Ledger ledger = new LedgerServiceImpl(ledgerRepository);
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository(), id -> false);
  private final FakePaymentNetwork network = new FakePaymentNetwork();
  private final InMemoryPaymentsRepository intentRepo = new InMemoryPaymentsRepository();
  private final MerchantsService merchants = new FakeMerchantsService();
  private final FakeBankAccountsService bankAccounts = new FakeBankAccountsService();
  private final PaymentsService payments =
      new PaymentsServiceImpl(ledger, accounts, network, intentRepo, event -> { }, merchants);
  private final InMemoryPayoutsRepository payoutRepo = new InMemoryPayoutsRepository();
  // Outbox publishing is covered by the integration suites; unit scope ignores events.
  private final PayoutsService payouts =
      new PayoutsServiceImpl(ledger, accounts, network, payoutRepo, event -> { }, merchants,
          bankAccounts);

  PayoutsServiceImplTest() {
    // Test-scope composition layer: mirror the V5/V18 seeds so the pooled
    // clearing asset and payout-reserve liability exist before the lifecycle
    // posts against them.
    ledgerRepository.insertAccount(new LedgerAccount(PaymentClearingAccount.PUBLIC_ID,
        "psp clearing", AccountType.ASSET, Currency.getInstance("BRL"),
        AccountStatus.ACTIVE, Instant.now(), null));
    ledgerRepository.insertAccount(new LedgerAccount(PayoutReservedAccount.PUBLIC_ID,
        "payout reserve", AccountType.LIABILITY, Currency.getInstance("BRL"),
        AccountStatus.ACTIVE, Instant.now(), null));
  }

  @Test
  void cancelLostToAParallelPaySettles() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("100.0000"), null));
    network.succeed(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var bankAccount = UUID.randomUUID();
    bankAccounts.armVerified(bankAccount, "bank.cancel-lost-01");
    var payout = payouts.create(SeedMerchant.PUBLIC_ID, new CreatePayoutCommand(
        account.publicId(), Money.ofBrl("30.0000"), bankAccount, null));

    // The read is past expiry while the instruction still polls PENDING; the
    // armed fake has the cancel land after a parallel pay already won the
    // status-guarded row, so the attempt observes the winner's state.
    payoutRepo.agePastExpiry(payout.publicId());
    network.loseCancelToSettlement(payout.transferPublicId());
    assertEquals(ChargeStatus.PENDING,
        network.getPayoutTransfer(payout.transferPublicId()).status());

    var settled = payouts.get(SeedMerchant.PUBLIC_ID, payout.publicId());

    // The cancel losing means the money moved: the payout settles out of the
    // reserve instead of expiring back to the merchant.
    assertEquals(PayoutStatus.SETTLED, settled.status());
    assertNotNull(settled.executeTransactionPublicId());
    assertNull(settled.returnTransactionPublicId());
    assertNotNull(settled.settledAt());
    assertEquals(0, ledger.balance(PayoutReservedAccount.PUBLIC_ID)
        .compareTo(Money.ofBrl("0.0000")));
    assertEquals(0, accounts.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
  }
}
