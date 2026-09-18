package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookPublishTest extends IntegrationTestBase {

  @Autowired
  private PaymentsService payments;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private SimulatorService simulator;

  @Autowired
  private WebhookStore store;

  private UUID registerEndpoint() {
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(),
        URI.create("https://merchant.example/publish-" + UUID.randomUUID()),
        "whsec_publish", List.of(), EndpointStatus.ACTIVE, Instant.now())).publicId();
  }

  @Test
  void settledIntentPublishesOneEventAndOneDeliveryPerSubscriber() throws Exception {
    var endpointId = registerEndpoint();
    var account = accountsService.open(new OpenAccountCommand("Publish Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("9.0000"), null));
    simulator.pay(intent.chargePublicId());

    payments.get(intent.publicId()); // settles and publishes

    var deliveries = store.listDeliveries(endpointId, null, 50);
    assertEquals(1, deliveries.size());
    assertEquals("payment_intent.settled", deliveries.get(0).eventType());
    assertEquals("PENDING", deliveries.get(0).status());

    // The stored payload is the envelope: string amount, settled state, journal link.
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery("SELECT payload FROM webhooks.webhook_event ORDER BY id DESC LIMIT 1")) {
      rs.next();
      String payload = rs.getString(1);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"type\":\"payment_intent.settled\""), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"amount\":\"9.0000\""), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains(intent.publicId().toString()), payload);
      org.junit.jupiter.api.Assertions.assertTrue(payload.contains("journalTransactionId"), payload);
    }
  }

  @Test
  void rolledBackSettlementPublishesNothingForThatIntent() throws Exception {
    registerEndpoint();
    var account = accountsService.open(new OpenAccountCommand("Rollback Merchant"));
    var intent = payments.create(new CreateIntentCommand(account.publicId(), Money.ofBrl("4.0000"), null));
    simulator.pay(intent.chargePublicId());
    accountsService.freeze(account.publicId()); // settle refuses, transaction rolls back

    assertThrows(RuntimeException.class, () -> payments.get(intent.publicId()));

    // Scoped: no event may reference this intent (the shared container legitimately
    // holds other classes' events — never assert global counts).
    try (var c = adminConnection(); var st = c.createStatement();
        var rs = st.executeQuery(
            "SELECT count(*) FROM webhooks.webhook_event WHERE payload LIKE '%" + intent.publicId() + "%'")) {
      rs.next();
      assertEquals(0, rs.getInt(1));
    }
  }

  @Test
  void failedAndExpiredIntentsPublishTheirTypes() throws Exception {
    var endpointId = registerEndpoint();
    var failedAccount = accountsService.open(new OpenAccountCommand("Failed Merchant"));
    var failed = payments.create(new CreateIntentCommand(failedAccount.publicId(), Money.ofBrl("3.0000"), null));
    simulator.fail(failed.chargePublicId());
    payments.get(failed.publicId());

    var expiredAccount = accountsService.open(new OpenAccountCommand("Expired Merchant"));
    var expired = payments.create(new CreateIntentCommand(expiredAccount.publicId(), Money.ofBrl("2.0000"), null));
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE payments.payment_intent SET expires_at = now() - interval '1 second' "
          + "WHERE public_id = '" + expired.publicId() + "'");
    }
    payments.get(expired.publicId());

    var deliveries = store.listDeliveries(endpointId, null, 50);
    assertEquals(2, deliveries.size());
    // listDeliveries orders by delivery id DESC: the expired event (published
    // second) comes first.
    assertEquals("payment_intent.expired", deliveries.get(0).eventType());
    assertEquals("payment_intent.failed", deliveries.get(1).eventType());
  }
}
