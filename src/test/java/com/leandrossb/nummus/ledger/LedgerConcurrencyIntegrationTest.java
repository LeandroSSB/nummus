package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerConcurrencyIntegrationTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final int THREADS = 8;
  private static final int POSTS_PER_THREAD = 25;

  @Autowired
  private Ledger ledger;

  @Test
  @Timeout(120)
  void concurrentPostsAllCommitAndBalanceConvergesExactly() throws Exception {
    var asset = ledger.openAccount(new OpenAccountCommand("concurrency cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(
        new OpenAccountCommand("concurrency payable", AccountType.LIABILITY, BRL));

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          for (int j = 0; j < POSTS_PER_THREAD; j++) {
            ledger.post(new PostTransactionCommand("concurrent", List.of(
                new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
                new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(90, TimeUnit.SECONDS); // rethrows the first worker failure, if any
      }
    } finally {
      pool.shutdownNow();
    }

    int expectedPosts = THREADS * POSTS_PER_THREAD;
    assertEquals(0, ledger.balance(asset.publicId())
        .compareTo(Money.ofBrl(String.valueOf(expectedPosts) + ".0000")));
    assertEquals(0, ledger.balance(liability.publicId())
        .compareTo(Money.ofBrl("-" + expectedPosts + ".0000")));
  }
}
