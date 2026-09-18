package com.leandrossb.nummus.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerServiceAccountsTest {

  private static final Currency BRL = Currency.getInstance("BRL");
  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());

  @Test
  void openAccountDefaultsToActiveWithPublicId() {
    var account = ledger.openAccount(new OpenAccountCommand("merchant payable",
        AccountType.LIABILITY, BRL));
    assertNotNull(account.publicId());
    assertEquals(AccountStatus.ACTIVE, account.status());
    assertEquals(BRL, account.currency());
    assertNull(account.closedAt());
  }

  @Test
  void openAccountRejectsNonBrlCurrency() {
    assertThrows(CurrencyMismatchException.class, () ->
        ledger.openAccount(new OpenAccountCommand("usd account", AccountType.ASSET,
            Currency.getInstance("USD"))));
  }

  @Test
  void openAccountRejectsBlankNameAndMissingType() {
    assertThrows(IllegalArgumentException.class, () ->
        ledger.openAccount(new OpenAccountCommand("  ", AccountType.ASSET, BRL)));
    assertThrows(NullPointerException.class, () ->
        ledger.openAccount(new OpenAccountCommand("name", null, BRL)));
  }

  @Test
  void freezeAndCloseTransitionStatus() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(AccountStatus.FROZEN, ledger.freezeAccount(account.publicId()).status());
    assertEquals(AccountStatus.ACTIVE, ledger.openAccount(
        new OpenAccountCommand("b", AccountType.ASSET, BRL)).status()); // sanity
    var closed = ledger.closeAccount(account.publicId());
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertNotNull(closed.closedAt());
  }

  @Test
  void closedIsTerminalAndUnknownAccountsThrow() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    ledger.closeAccount(account.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.freezeAccount(account.publicId()));
    assertThrows(AccountNotActiveException.class, () -> ledger.closeAccount(account.publicId()));
    assertThrows(UnknownAccountException.class, () -> ledger.freezeAccount(UUID.randomUUID()));
    assertThrows(UnknownAccountException.class, () -> ledger.balance(UUID.randomUUID()));
    assertThrows(UnknownAccountException.class,
        () -> ledger.statement(UUID.randomUUID(), new com.leandrossb.nummus.ledger.domain.Page(0, 50)));
  }

  @Test
  void balanceOfAccountWithoutPostingsIsZero() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(0, ledger.balance(account.publicId()).compareTo(
        com.leandrossb.nummus.ledger.domain.Money.ofBrl("0.0000")));
  }

  @Test
  void unfreezeRestoresActiveAndClosedIsTerminal() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    ledger.freezeAccount(account.publicId());
    assertEquals(AccountStatus.ACTIVE, ledger.unfreezeAccount(account.publicId()).status());
    ledger.closeAccount(account.publicId());
    assertThrows(AccountNotActiveException.class, () -> ledger.unfreezeAccount(account.publicId()));
    assertThrows(UnknownAccountException.class, () -> ledger.unfreezeAccount(UUID.randomUUID()));
  }

  @Test
  void getAccountReturnsLedgerAccountOrThrows() {
    var account = ledger.openAccount(new OpenAccountCommand("a", AccountType.ASSET, BRL));
    assertEquals(account.publicId(), ledger.getAccount(account.publicId()).publicId());
    assertEquals(AccountType.ASSET, ledger.getAccount(account.publicId()).type());
    assertThrows(UnknownAccountException.class, () -> ledger.getAccount(UUID.randomUUID()));
  }
}
