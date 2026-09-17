package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.OpenAccountCommand;
import com.leandrossb.nummus.ledger.application.PostTransactionCommand;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerServiceIntegrationTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private Ledger ledger;

  @Test
  void postingCycleWithReversalRestoresBalances() {
    var asset = ledger.openAccount(new OpenAccountCommand("e2e cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(new OpenAccountCommand("e2e payable", AccountType.LIABILITY, BRL));
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0")));

    var tx = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));

    var reversal = ledger.reverse(tx.publicId(), "undo funding");
    assertEquals(tx.publicId(), reversal.reversalOf());
    assertEquals(Direction.CREDIT, reversal.postings().get(0).direction());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("0")));

    assertThrows(TransactionAlreadyReversedException.class, () -> ledger.reverse(tx.publicId(), "again"));
    var redo = ledger.reverse(reversal.publicId(), "redo funding");
    assertEquals(reversal.publicId(), redo.reversalOf());
    assertEquals(0, ledger.balance(asset.publicId()).compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void reverseToNonActiveAccountThrowsDomainExceptionNotAnInfrastructureError() {
    var asset = ledger.openAccount(new OpenAccountCommand("frozen-rev cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(
        new OpenAccountCommand("frozen-rev payable", AccountType.LIABILITY, BRL));
    var tx = ledger.post(new PostTransactionCommand("funding", List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("25.0000")),
        new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("25.0000")))));
    ledger.freezeAccount(liability.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.reverse(tx.publicId(), "late undo"));
  }

  @Test
  void lifecycleGuardsHoldAgainstRealDatabase() {
    var asset = ledger.openAccount(new OpenAccountCommand("guard cash", AccountType.ASSET, BRL));
    var counterparty = ledger.openAccount(new OpenAccountCommand("guard cp", AccountType.LIABILITY, BRL));
    var drafts = List.of(
        new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("1.0000")),
        new PostingDraft(counterparty.publicId(), Direction.CREDIT, Money.ofBrl("1.0000")));

    ledger.freezeAccount(asset.publicId());
    assertThrows(AccountNotActiveException.class,
        () -> ledger.post(new PostTransactionCommand("frozen", drafts)));

    ledger.closeAccount(counterparty.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.closeAccount(counterparty.publicId()));

    assertThrows(UnknownAccountException.class, () -> ledger.balance(UUID.randomUUID()));
    assertThrows(UnknownTransactionException.class, () -> ledger.getTransaction(UUID.randomUUID()));
  }

  @Test
  void statementPairsPostingsWithDerivedBalance() {
    var asset = ledger.openAccount(new OpenAccountCommand("stmt cash", AccountType.ASSET, BRL));
    var liability = ledger.openAccount(new OpenAccountCommand("stmt cp", AccountType.LIABILITY, BRL));
    for (int i = 1; i <= 3; i++) {
      ledger.post(new PostTransactionCommand("stmt-" + i, List.of(
          new PostingDraft(asset.publicId(), Direction.DEBIT, Money.ofBrl("2.0000")),
          new PostingDraft(liability.publicId(), Direction.CREDIT, Money.ofBrl("2.0000")))));
    }
    var statement = ledger.statement(asset.publicId(), new Page(0, 2));
    assertEquals(asset.publicId(), statement.account().publicId());
    assertEquals(0, statement.balance().compareTo(Money.ofBrl("6.0000")));
    assertEquals(2, statement.lines().size());
    assertEquals("stmt-3", statement.lines().get(0).memo());
    assertTrue(statement.lines().get(0).bookedAt() != null);
  }
}
