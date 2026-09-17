package com.leandrossb.nummus.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.AccountStatus;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.LedgerAccount;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class LedgerRepositoryAccountsTest extends IntegrationTestBase {

  private static final Currency BRL = Currency.getInstance("BRL");

  @Autowired
  private com.leandrossb.nummus.ledger.application.LedgerRepository repository;

  @Test
  void insertAndFindAccountRoundTrips() {
    var account = new LedgerAccount(UUID.randomUUID(), "merchant payable",
        AccountType.LIABILITY, BRL, AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);

    var found = repository.findAccount(account.publicId()).orElseThrow();
    assertEquals(account.publicId(), found.publicId());
    assertEquals("merchant payable", found.name());
    assertEquals(AccountType.LIABILITY, found.type());
    assertEquals(BRL, found.currency());
    assertEquals(AccountStatus.ACTIVE, found.status());
    assertTrue(found.closedAt() == null);
  }

  @Test
  void findAccountReturnsEmptyForUnknownId() {
    assertTrue(repository.findAccount(UUID.randomUUID()).isEmpty());
  }

  @Test
  void updateAccountStatusTransitionsAndRecordsCloseTime() {
    var account = new LedgerAccount(UUID.randomUUID(), "transient", AccountType.ASSET,
        BRL, AccountStatus.ACTIVE, Instant.now(), null);
    repository.insertAccount(account);

    assertTrue(repository.updateAccountStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repository.findAccount(account.publicId()).orElseThrow().status());

    Instant closedAt = Instant.now();
    assertTrue(repository.updateAccountStatus(account.publicId(), AccountStatus.CLOSED, closedAt));
    var closed = repository.findAccount(account.publicId()).orElseThrow();
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertTrue(closed.closedAt() != null);
    assertEquals(closedAt.toEpochMilli() / 1000, closed.closedAt().toEpochMilli() / 1000);

    assertTrue(!repository.updateAccountStatus(UUID.randomUUID(), AccountStatus.FROZEN, null));
  }
}
