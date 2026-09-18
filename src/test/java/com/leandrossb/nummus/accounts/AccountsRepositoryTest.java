package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsRepository;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountsRepositoryTest extends IntegrationTestBase {

  @Autowired
  private AccountsRepository repository;

  private PaymentAccount newAccount(String holderName) {
    return new PaymentAccount(UUID.randomUUID(), holderName, AccountStatus.ACTIVE,
        Instant.now(), null, UUID.randomUUID());
  }

  @Test
  void insertAndFindByPublicIdRoundTrip() {
    var account = newAccount("merchant one");
    repository.insert(account);

    var found = repository.findByPublicId(account.publicId()).orElseThrow();
    assertEquals(account.publicId(), found.publicId());
    assertEquals("merchant one", found.holderName());
    assertEquals(AccountStatus.ACTIVE, found.status());
    assertEquals(account.ledgerAccountPublicId(), found.ledgerAccountPublicId());
    assertEquals(found.openedAt().truncatedTo(ChronoUnit.MILLIS),
        account.openedAt().truncatedTo(ChronoUnit.MILLIS));
    assertTrue(found.closedAt() == null);
  }

  @Test
  void findByPublicIdReturnsEmptyForUnknownId() {
    assertTrue(repository.findByPublicId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void updateStatusTransitionsAndRecordsCloseTime() {
    var account = newAccount("transient");
    repository.insert(account);

    assertTrue(repository.updateStatus(account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repository.findByPublicId(account.publicId()).orElseThrow().status());

    Instant closedAt = Instant.now();
    assertTrue(repository.updateStatus(account.publicId(), AccountStatus.CLOSED, closedAt));
    var closed = repository.findByPublicId(account.publicId()).orElseThrow();
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertEquals(closedAt.truncatedTo(ChronoUnit.MILLIS), closed.closedAt().truncatedTo(ChronoUnit.MILLIS));

    assertTrue(!repository.updateStatus(UUID.randomUUID(), AccountStatus.FROZEN, null));
  }
}
