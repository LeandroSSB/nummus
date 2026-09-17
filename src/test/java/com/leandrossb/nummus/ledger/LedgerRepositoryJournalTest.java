package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.application.LedgerRepository;
import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.PostedPosting;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class LedgerRepositoryJournalTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private LedgerRepository repository;

  private LedgerAccount newAccount(String name, AccountType type) {
    var account = new LedgerAccount(UUID.randomUUID(), name, type, BRL,
        AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);
    return account;
  }

  @Test
  void insertAndFindTransactionRoundTrip() {
    var asset = newAccount("journal asset", AccountType.ASSET);
    var liability = newAccount("journal liability", AccountType.LIABILITY);
    var posted = repository.insertTransaction("round trip", null, List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("12.3456")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("12.3456"))));

    var found = repository.findTransaction(posted.publicId()).orElseThrow();
    assertEquals(posted.publicId(), found.publicId());
    assertEquals("round trip", found.memo());
    assertEquals(2, found.postings().size());
    PostedPosting first = found.postings().get(0);
    assertEquals(asset.publicId(), first.accountPublicId());
    assertEquals(Direction.DEBIT, first.direction());
    assertEquals(0, first.amount().compareTo(Money.ofBrl("12.3456")));
    assertTrue(found.reversalOf() == null);
    assertTrue(found.bookedAt() != null);
  }

  @Test
  void rawBalanceSumsDebitsMinusCredits() {
    var asset = newAccount("balance asset", AccountType.ASSET);
    var liability = newAccount("balance liability", AccountType.LIABILITY);
    assertEquals(0, repository.rawBalance(asset.publicId()).compareTo(BigDecimal.ZERO));

    repository.insertTransaction("in", null, List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("100.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("100.0000"))));
    repository.insertTransaction("partial out", null, List.of(
        new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("30.0000")),
        new PostingDraft(liability.publicId(), Direction.DEBIT, Money.ofBrl("30.0000"))));

    assertEquals(0, repository.rawBalance(asset.publicId()).compareTo(new BigDecimal("70.0000")));
    assertEquals(0, repository.rawBalance(liability.publicId()).compareTo(new BigDecimal("-70.0000")));
  }

  @Test
  void statementLinesAreNewestFirstAndPaginated() throws Exception {
    var asset = newAccount("statement asset", AccountType.ASSET);
    var liability = newAccount("statement liability", AccountType.LIABILITY);
    for (int i = 1; i <= 3; i++) {
      repository.insertTransaction("tx-" + i, null, List.of(
          new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
          new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))));
      Thread.sleep(10); // distinct booked_at timestamps
    }
    var lines = repository.statementLines(asset.publicId(), 0, 2);
    assertEquals(2, lines.size());
    assertEquals("tx-3", lines.get(0).memo());
    assertEquals("tx-2", lines.get(1).memo());
    assertEquals(Direction.DEBIT, lines.get(0).direction());
    assertTrue(lines.get(0).transactionPublicId() != null);

    var rest = repository.statementLines(asset.publicId(), 2, 2);
    assertEquals(1, rest.size());
    assertEquals("tx-1", rest.get(0).memo());
  }

  @Test
  void duplicateReversalSurfacesAsDataIntegrityViolation() {
    var asset = newAccount("reversal asset", AccountType.ASSET);
    var liability = newAccount("reversal liability", AccountType.LIABILITY);
    var drafts = List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("5.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("5.0000")));
    var original = repository.insertTransaction("original", null, drafts);
    repository.insertTransaction("first reversal", original.publicId(), drafts);

    assertThrows(DataIntegrityViolationException.class,
        () -> repository.insertTransaction("second reversal", original.publicId(), drafts));
    // findTransaction exposes the reversal link as a public id
    var first = repository.findTransaction(
        repository.insertTransaction("chain base", null, drafts).publicId()).orElseThrow();
    assertTrue(first.reversalOf() == null);
  }
}
