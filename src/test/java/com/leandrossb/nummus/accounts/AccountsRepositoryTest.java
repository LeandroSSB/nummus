package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.accounts.application.AccountsRepository;
import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.merchants.application.SeedMerchant;
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
    return new PaymentAccount(SeedMerchant.PUBLIC_ID, UUID.randomUUID(), holderName, AccountStatus.ACTIVE,
        Instant.now(), null, UUID.randomUUID());
  }

  @Test
  void insertAndFindByPublicIdRoundTrip() {
    var account = newAccount("merchant one");
    repository.insert(account);

    var found = repository.findByPublicId(SeedMerchant.PUBLIC_ID, account.publicId()).orElseThrow();
    assertEquals(account.publicId(), found.publicId());
    assertEquals("merchant one", found.holderName());
    assertEquals(AccountStatus.ACTIVE, found.status());
    assertEquals(account.ledgerAccountPublicId(), found.ledgerAccountPublicId());
    assertEquals(found.openedAt().truncatedTo(ChronoUnit.MILLIS),
        account.openedAt().truncatedTo(ChronoUnit.MILLIS));
    assertTrue(found.closedAt() == null);

    // Another merchant never sees this merchant's account.
    assertTrue(repository.findByPublicId(UUID.randomUUID(), account.publicId()).isEmpty());
  }

  @Test
  void findByPublicIdReturnsEmptyForUnknownId() {
    assertTrue(repository.findByPublicId(SeedMerchant.PUBLIC_ID, UUID.randomUUID()).isEmpty());
  }

  @Test
  void updateStatusTransitionsAndRecordsCloseTime() {
    var account = newAccount("transient");
    repository.insert(account);

    assertTrue(repository.updateStatus(SeedMerchant.PUBLIC_ID, account.publicId(), AccountStatus.FROZEN, null));
    assertEquals(AccountStatus.FROZEN, repository.findByPublicId(SeedMerchant.PUBLIC_ID, account.publicId()).orElseThrow().status());

    Instant closedAt = Instant.now();
    assertTrue(repository.updateStatus(SeedMerchant.PUBLIC_ID, account.publicId(), AccountStatus.CLOSED, closedAt));
    var closed = repository.findByPublicId(SeedMerchant.PUBLIC_ID, account.publicId()).orElseThrow();
    assertEquals(AccountStatus.CLOSED, closed.status());
    assertEquals(closedAt.truncatedTo(ChronoUnit.MILLIS), closed.closedAt().truncatedTo(ChronoUnit.MILLIS));

    assertTrue(!repository.updateStatus(SeedMerchant.PUBLIC_ID, UUID.randomUUID(), AccountStatus.FROZEN, null));
    assertTrue(!repository.updateStatus(UUID.randomUUID(), account.publicId(), AccountStatus.FROZEN, null));
  }
}
