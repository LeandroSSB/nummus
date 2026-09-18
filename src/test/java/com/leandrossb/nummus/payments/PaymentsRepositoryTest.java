package com.leandrossb.nummus.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.PaymentsRepository;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.IntentStatus;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentsRepositoryTest extends IntegrationTestBase {

  @Autowired
  private PaymentsRepository repository;

  private PaymentIntent newIntent() {
    return new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), Money.ofBrl("10.0000"),
        IntentStatus.CREATED, UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(30)),
        Instant.now(), null, null);
  }

  @Test
  void insertAndFindByPublicIdRoundTrip() {
    var intent = newIntent();
    repository.insert(intent);

    var found = repository.findByPublicId(intent.publicId()).orElseThrow();
    assertEquals(intent.publicId(), found.publicId());
    assertEquals(intent.accountPublicId(), found.accountPublicId());
    assertEquals(0, found.amount().compareTo(Money.ofBrl("10.0000")));
    assertEquals(IntentStatus.CREATED, found.status());
    assertEquals(intent.chargePublicId(), found.chargePublicId());
    assertTrue(found.settledAt() == null);
    assertTrue(found.journalTransactionPublicId() == null);
  }

  @Test
  void findByPublicIdReturnsEmptyForUnknownId() {
    assertTrue(repository.findByPublicId(UUID.randomUUID()).isEmpty());
  }

  @Test
  void guardedTransitionsApplyOnceThenRefuse() {
    var intent = newIntent();
    repository.insert(intent);

    assertTrue(repository.transitionToExpired(intent.publicId()));
    assertEquals(IntentStatus.EXPIRED, repository.findByPublicId(intent.publicId()).orElseThrow().status());
    assertFalse(repository.transitionToExpired(intent.publicId()));

    var other = newIntent();
    repository.insert(other);
    var journalTx = UUID.randomUUID();
    assertTrue(repository.markSettled(other.publicId(), journalTx, Instant.now()));
    var settled = repository.findByPublicId(other.publicId()).orElseThrow();
    assertEquals(IntentStatus.SETTLED, settled.status());
    assertEquals(journalTx, settled.journalTransactionPublicId());
    assertTrue(settled.settledAt() != null);
    assertFalse(repository.markSettled(other.publicId(), UUID.randomUUID(), Instant.now()));

    var third = newIntent();
    repository.insert(third);
    assertTrue(repository.transitionToFailed(third.publicId()));
    assertFalse(repository.transitionToFailed(third.publicId()));
  }
}
