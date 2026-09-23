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
import com.leandrossb.nummus.merchants.application.FakeMerchantsService;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.CreateRefundCommand;
import com.leandrossb.nummus.payments.domain.RefundStatus;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * Focused service-level pins for {@code RefundsServiceImpl.get}'s expiry
 * resolution that the simulator cannot drive deterministically: the
 * cancel-vs-pay interleave is emulated through the test-only boundary double
 * {@link FakePaymentNetwork} (the {@code PayoutsServiceImplTest} precedent —
 * production paths never touch the fake).
 */
class RefundsServiceImplTest {

  private final InMemoryLedgerRepository ledgerRepository = new InMemoryLedgerRepository();
  private final Ledger ledger = new LedgerServiceImpl(ledgerRepository);
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository(), id -> false);
  private final FakePaymentNetwork network = new FakePaymentNetwork();
  private final InMemoryPaymentsRepository intentRepo = new InMemoryPaymentsRepository();
  private final MerchantsService merchants = new FakeMerchantsService();
  private final PaymentsService payments =
      new PaymentsServiceImpl(ledger, accounts, network, intentRepo, event -> { }, merchants);
  private final InMemoryRefundsRepository refundRepo = new InMemoryRefundsRepository();
  // Outbox publishing is covered by the integration suites; unit scope ignores events.
  private final RefundsService refunds =
      new RefundsServiceImpl(ledger, accounts, payments, network, refundRepo, event -> { });

  RefundsServiceImplTest() {
    // Test-scope composition layer: mirror the V5/V19 seeds so the pooled
    // clearing asset and refund-reserve liability exist before the lifecycle
    // posts against them.
    ledgerRepository.insertAccount(new LedgerAccount(PaymentClearingAccount.PUBLIC_ID,
        "psp clearing", AccountType.ASSET, Currency.getInstance("BRL"),
        AccountStatus.ACTIVE, Instant.now(), null));
    ledgerRepository.insertAccount(new LedgerAccount(RefundReservedAccount.PUBLIC_ID,
        "refund reserve", AccountType.LIABILITY, Currency.getInstance("BRL"),
        AccountStatus.ACTIVE, Instant.now(), null));
  }

  @Test
  void cancelLostToAParallelPaySettles() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("merchant"));
    var intent = payments.create(SeedMerchant.PUBLIC_ID,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("100.0000"), null));
    network.succeed(intent.chargePublicId());
    payments.get(SeedMerchant.PUBLIC_ID, intent.publicId());
    var refund = refunds.create(SeedMerchant.PUBLIC_ID, intent.publicId(),
        new CreateRefundCommand(Money.ofBrl("30.0000"), null));

    // The read is past expiry while the instruction still polls PENDING; the
    // armed fake has the cancel land after a parallel pay already won the
    // status-guarded row, so the attempt observes the winner's state.
    refundRepo.agePastExpiry(refund.publicId());
    network.loseRefundCancelToSettlement(refund.networkRefundPublicId());
    assertEquals(ChargeStatus.PENDING,
        network.getChargeRefund(refund.networkRefundPublicId()).status());

    var settled = refunds.get(SeedMerchant.PUBLIC_ID, refund.publicId());

    // The cancel losing means the money moved: the refund settles out of the
    // reserve instead of expiring back to the merchant.
    assertEquals(RefundStatus.SETTLED, settled.status());
    assertNotNull(settled.executeTransactionPublicId());
    assertNull(settled.returnTransactionPublicId());
    assertNotNull(settled.settledAt());
    assertEquals(0, ledger.balance(RefundReservedAccount.PUBLIC_ID)
        .compareTo(Money.ofBrl("0.0000")));
    assertEquals(0, accounts.balance(SeedMerchant.PUBLIC_ID, account.publicId())
        .compareTo(Money.ofBrl("70.0000")));
  }
}
