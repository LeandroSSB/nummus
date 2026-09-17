package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.Direction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryLedgerRepositoryTest {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Test
  void accountsRoundTrip() {
    var repo = new InMemoryLedgerRepository();
    var account = new LedgerAccount(UUID.randomUUID(), "cash", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(account);
    assertEquals(account, repo.findAccount(account.publicId()).orElseThrow());
    assertTrue(repo.updateAccountStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repo.findAccount(account.publicId()).orElseThrow().status());
  }

  @Test
  void transactionsRoundTripAndBalanceSums() {
    var repo = new InMemoryLedgerRepository();
    var debit = new LedgerAccount(UUID.randomUUID(), "debit", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    var credit = new LedgerAccount(UUID.randomUUID(), "credit", AccountType.LIABILITY, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(debit);
    repo.insertAccount(credit);

    var posted = repo.insertTransaction("t1", null, List.of(
        new PostingDraft(debit.publicId(), Direction.DEBIT, Money.ofBrl("10.0000")),
        new PostingDraft(credit.publicId(), Direction.CREDIT, Money.ofBrl("10.0000"))));
    assertEquals(posted, repo.findTransaction(posted.publicId()).orElseThrow());
    assertEquals(0, repo.rawBalance(debit.publicId()).compareTo(new BigDecimal("10.0000")));
    assertEquals(0, repo.rawBalance(credit.publicId()).compareTo(new BigDecimal("-10.0000")));
  }

  @Test
  void duplicateReversalThrowsDataIntegrityViolation() {
    var repo = new InMemoryLedgerRepository();
    var debit = new LedgerAccount(UUID.randomUUID(), "d", AccountType.ASSET, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    var credit = new LedgerAccount(UUID.randomUUID(), "c", AccountType.LIABILITY, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repo.insertAccount(debit);
    repo.insertAccount(credit);
    var drafts = List.of(
        new PostingDraft(debit.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(credit.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")));
    var original = repo.insertTransaction("t1", null, drafts);
    repo.insertTransaction("reversal", original.publicId(), drafts);

    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> repo.insertTransaction("again", original.publicId(), drafts));
  }
}
