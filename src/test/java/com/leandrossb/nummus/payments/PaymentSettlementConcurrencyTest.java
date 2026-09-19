package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentSettlementConcurrencyTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  @Test
  @Timeout(120)
  void racingSettlersPostExactlyOneJournalEntry() throws Exception {
    var account = accountsService.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("Race Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("20.0000"), null));
    simulator.pay(intent.chargePublicId());

    ExecutorService pool = Executors.newFixedThreadPool(8);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 8; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          try {
            payments.get(intent.publicId());
            return Boolean.TRUE;
          } catch (RuntimeException e) {
            return Boolean.FALSE; // ConcurrentSettlementException losers are expected
          }
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(90, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    // Exactly-once proof: the intent is settled and the merchant was credited
    // exactly the intent amount — a second settlement entry would double it.
    assertEquals(IntentStatus.SETTLED, payments.get(intent.publicId()).status());
    assertEquals(0, accountsService.balance(SeedMerchant.PUBLIC_ID, account.publicId()).compareTo(Money.ofBrl("20.0000")));
  }
}
