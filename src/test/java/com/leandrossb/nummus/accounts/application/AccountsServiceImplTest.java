package com.leandrossb.nummus.accounts.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.InMemoryLedgerRepository;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.application.LedgerServiceImpl;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import com.leandrossb.nummus.ledger.domain.PostingDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsServiceImplTest {

  private final Ledger ledger = new LedgerServiceImpl(new InMemoryLedgerRepository());
  private final AccountsService accounts =
      new AccountsServiceImpl(ledger, new InMemoryAccountsRepository(), id -> false);

  @Test
  void openCreatesActiveAccountWithBackingLiabilityLedgerAccount() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("  Merchant One  "));
    assertNotNull(account.publicId());
    assertEquals("Merchant One", account.holderName());
    assertEquals(AccountStatus.ACTIVE, account.status());
    assertNull(account.closedAt());
    var backing = ledger.getAccount(account.ledgerAccountPublicId());
    assertEquals(AccountType.LIABILITY, backing.type());
    assertTrue(backing.name().startsWith("payable "));
  }

  @Test
  void openRejectsBlankAndOversizedNames() {
    assertThrows(IllegalArgumentException.class, () -> accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand(null)));
    assertThrows(IllegalArgumentException.class, () -> accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("   ")));
    assertThrows(IllegalArgumentException.class,
        () -> accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("x".repeat(201))));
  }

  @Test
  void freezeUnfreezeCloseLifecycleWithTerminalClosed() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    assertEquals(AccountStatus.FROZEN, accounts.freeze(SeedMerchant.PUBLIC_ID, account.publicId()).status());
    assertEquals(AccountStatus.ACTIVE, accounts.unfreeze(SeedMerchant.PUBLIC_ID, account.publicId()).status());
    var closed = accounts.close(SeedMerchant.PUBLIC_ID, account.publicId());
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertNotNull(closed.closedAt());

    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.freeze(SeedMerchant.PUBLIC_ID, account.publicId()));
    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.unfreeze(SeedMerchant.PUBLIC_ID, account.publicId()));
    assertThrows(PaymentAccountNotActiveException.class, () -> accounts.close(SeedMerchant.PUBLIC_ID, account.publicId()));
  }

  @Test
  void unfreezeOnActiveAccountIsRejected() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    assertThrows(IllegalArgumentException.class, () -> accounts.unfreeze(SeedMerchant.PUBLIC_ID, account.publicId()));
  }

  @Test
  void ledgerAccountStatusMirrorsPaymentAccountStatus() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    accounts.freeze(SeedMerchant.PUBLIC_ID, account.publicId());
    var frozenBacking = ledger.getAccount(account.ledgerAccountPublicId());
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.FROZEN, frozenBacking.status());
    accounts.unfreeze(SeedMerchant.PUBLIC_ID, account.publicId());
    assertEquals(com.leandrossb.nummus.ledger.domain.AccountStatus.ACTIVE,
        ledger.getAccount(account.ledgerAccountPublicId()).status());
  }

  @Test
  void balancePresentsNaturalSignForCreditNormalBackingAccount() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new com.leandrossb.nummus.ledger.application.PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    assertEquals(0, accounts.balance(SeedMerchant.PUBLIC_ID, account.publicId()).compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void statementBalanceIsNaturalSignedWhileLinesStayAsPosted() {
    var account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    var house = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "house asset", AccountType.ASSET, java.util.Currency.getInstance("BRL")));
    ledger.post(new com.leandrossb.nummus.ledger.application.PostTransactionCommand("funding", List.of(
        new PostingDraft(house.publicId(), Direction.DEBIT, Money.ofBrl("150.0000")),
        new PostingDraft(account.ledgerAccountPublicId(), Direction.CREDIT, Money.ofBrl("150.0000")))));

    var statement = accounts.statement(SeedMerchant.PUBLIC_ID, account.publicId(), new Page(0, 10));
    assertEquals(0, statement.balance().compareTo(Money.ofBrl("150.0000")));
    assertEquals(1, statement.lines().size());
    assertEquals(Direction.CREDIT, statement.lines().get(0).direction());
    assertEquals(0, statement.lines().get(0).amount().compareTo(Money.ofBrl("150.0000")));
  }

  @Test
  void unknownIdsThrowUnknownPaymentAccount() {
    var id = UUID.randomUUID();
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.get(SeedMerchant.PUBLIC_ID, id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.freeze(SeedMerchant.PUBLIC_ID, id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.balance(SeedMerchant.PUBLIC_ID, id));
    assertThrows(UnknownPaymentAccountException.class, () -> accounts.statement(SeedMerchant.PUBLIC_ID, id, new Page(0, 10)));
  }

  @Test
  void getRoundTripsOpenedAccount() {
    PaymentAccount account = accounts.open(SeedMerchant.PUBLIC_ID, new OpenAccountCommand("m"));
    PaymentAccount found = accounts.get(SeedMerchant.PUBLIC_ID, account.publicId());
    assertEquals(account.publicId(), found.publicId());
    assertEquals(account.ledgerAccountPublicId(), found.ledgerAccountPublicId());
  }
}
