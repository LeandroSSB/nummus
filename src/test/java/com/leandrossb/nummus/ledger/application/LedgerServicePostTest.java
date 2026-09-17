package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.PostedTransaction;
import com.leandrossb.nummus.ledger.domain.TooFewPostingsException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnbalancedTransactionException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServicePostTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());

  private com.leandrossb.nummus.ledger.domain.LedgerAccount account(String name, AccountType type) {
    return ledger.openAccount(new OpenAccountCommand(name, type, BRL));
  }

  @Test
  void postReturnsCommittedTransactionAndUpdatesBalance() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    assertNotNull(posted.publicId());
    assertNotNull(posted.bookedAt());
    assertEquals(2, posted.postings().size());
    assertEquals("funding", posted.memo());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));
    assertEquals(0, ledger.balance(liability.publicId()).compareTo(Money.ofBrl("-150.0000")));
  }

  @Test
  void postRejectsTooFewOrOneSidedPostings() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var amount = Money.ofBrl("1.0000");
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("null postings", null)));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("empty", List.of())));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("single", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, amount)))));
    assertThrows(TooFewPostingsException.class, () ->
        ledger.post(new PostTransactionCommand("two debits", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, amount),
            new PostingDraft(liability.publicId(), Direction.DEBIT, amount)))));
  }

  @Test
  void postRejectsUnbalancedAmounts() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    assertThrows(UnbalancedTransactionException.class, () ->
        ledger.post(new PostTransactionCommand("unbalanced", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("10.0000")),
            new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("9.9999"))))));
  }

  @Test
  void postAcceptsBalancedAmountsWithDifferentScales() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("scale tolerant", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("10.0")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("10.0000")))));
    assertNotNull(posted.publicId());
  }

  @Test
  void postRejectsUnknownFrozenOrForeignCurrencyAccounts() {
    var asset = account("cash", AccountType.ASSET);
    assertThrows(UnknownAccountException.class, () ->
        ledger.post(new PostTransactionCommand("unknown", List.of(
            new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("1.0000")),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));

    var frozen = account("frozen cash", AccountType.ASSET);
    ledger.freezeAccount(frozen.publicId());
    assertThrows(AccountNotActiveException.class, () ->
        ledger.post(new PostTransactionCommand("frozen", List.of(
            new PostingDraft(frozen.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));

    assertThrows(CurrencyMismatchException.class, () ->
        ledger.post(new PostTransactionCommand("wrong currency", List.of(
            new PostingDraft(asset.publicId(), Direction.DEBIT,
                Money.of(BigDecimal.ONE, Currency.getInstance("USD"))),
            new PostingDraft(asset.publicId(), Direction.CREDIT, Money.ofBrl("1.0000"))))));
  }

  @Test
  void reverseMirrorsPostingsAndDoubleReverseThrows() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var original = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("50.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("50.0000")))));

    var reversal = ledger.reverse(original.publicId(), "undo funding");
    assertEquals(original.publicId(), reversal.reversalOf());
    assertEquals(2, reversal.postings().size());
    assertEquals(Direction.CREDIT, reversal.postings().get(0).direction());
    assertEquals(Direction.DEBIT, reversal.postings().get(1).direction());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0.0000")));

    assertThrows(TransactionAlreadyReversedException.class,
        () -> ledger.reverse(original.publicId(), "again"));
    assertThrows(UnknownTransactionException.class,
        () -> ledger.reverse(UUID.randomUUID(), "nope"));
  }

  @Test
  void reversingAReversalIsAllowedAndRestoresBalance() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var original = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("50.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("50.0000")))));
    var reversal = ledger.reverse(original.publicId(), "undo");
    ledger.reverse(reversal.publicId(), "redo");
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("50.0000")));
  }

  @Test
  void getTransactionRoundTrips() {
    var asset = account("cash", AccountType.ASSET);
    var liability = account("payable", AccountType.LIABILITY);
    var posted = ledger.post(new PostTransactionCommand("t", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")))));
    PostedTransaction fetched = ledger.getTransaction(posted.publicId());
    assertEquals(posted.publicId(), fetched.publicId());
    assertThrows(UnknownTransactionException.class, () -> ledger.getTransaction(UUID.randomUUID()));
  }
}
