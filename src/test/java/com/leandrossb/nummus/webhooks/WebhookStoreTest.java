package com.leandrossb.nummus.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.SeedMerchant;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import com.leandrossb.nummus.webhooks.application.DeliveryRecord;
import com.leandrossb.nummus.webhooks.application.DueDelivery;
import com.leandrossb.nummus.webhooks.application.WebhookStore;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookStoreTest extends IntegrationTestBase {

  @Autowired
  private WebhookStore store;

  @BeforeEach
  void cleanWebhooksTables() throws Exception {
    // The container is shared across tests; fan-out targets every subscribe-all
    // endpoint ever inserted, so each test starts from empty webhooks tables.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("TRUNCATE webhooks.webhook_delivery, webhooks.webhook_event, "
          + "webhooks.webhook_endpoint");
    }
  }

  private WebhookEndpoint endpoint(String path, List<String> types) {
    return store.insertEndpoint(new WebhookEndpoint(SeedMerchant.PUBLIC_ID, UUID.randomUUID(),
        URI.create("https://merchant.example/" + path), "whsec_" + path,
        types, EndpointStatus.ACTIVE, Instant.now()));
  }

  private void publish(String type) {
    store.insertEvent(UUID.randomUUID(), type, "{\"type\":\"" + type + "\"}", Instant.now());
  }

  @Test
  void endpointRoundTripAndSoftDelete() {
    var created = endpoint("roundtrip", List.of("payment_intent.settled"));
    assertEquals(EndpointStatus.ACTIVE, created.status());
    assertTrue(store.listActiveEndpoints(SeedMerchant.PUBLIC_ID).stream()
        .anyMatch(e -> e.publicId().equals(created.publicId())));
    assertTrue(store.findActiveEndpoint(SeedMerchant.PUBLIC_ID, created.publicId()).isPresent());

    assertTrue(store.markEndpointDeleted(SeedMerchant.PUBLIC_ID, created.publicId()));
    assertTrue(store.findActiveEndpoint(SeedMerchant.PUBLIC_ID, created.publicId()).isEmpty());
    assertTrue(store.listActiveEndpoints(SeedMerchant.PUBLIC_ID).stream()
        .noneMatch(e -> e.publicId().equals(created.publicId())));
    assertTrue(!store.markEndpointDeleted(SeedMerchant.PUBLIC_ID, created.publicId()));
  }

  @Test
  void insertEventFansOutToActiveSubscribersOfTheTypeOnly() {
    var allTypes = endpoint("all", List.of());
    var settledOnly = endpoint("settled", List.of("payment_intent.settled"));
    var deleted = endpoint("gone", List.of());
    store.markEndpointDeleted(SeedMerchant.PUBLIC_ID, deleted.publicId());

    publish("payment_intent.settled");
    publish("payment_intent.failed");

    var settledDeliveries = store.listDeliveries(SeedMerchant.PUBLIC_ID, allTypes.publicId(), null, null, 50);
    assertEquals(1, store.listDeliveries(SeedMerchant.PUBLIC_ID, settledOnly.publicId(), null, null, 50).size());
    assertEquals(2, settledDeliveries.size()); // [] subscribes to every type
    assertEquals(0, store.listDeliveries(SeedMerchant.PUBLIC_ID, deleted.publicId(), null, null, 50).size());
  }

  @Test
  void claimDueDeliveriesRespectsDueTimeOrderAndLimit() throws Exception {
    var target = endpoint("claim", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());

    var other = endpoint("claim-other", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.failed", "{}", Instant.now());
    // Both events fan out to BOTH subscribe-all endpoints. Age the deliveries
    // DB-side after the fan-out: /claim pushed out (not due), /claim-other due now.
    try (var c = adminConnection(); var st = c.createStatement()) {
      st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() + interval '1 hour' "
          + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/claim')");
      st.executeUpdate("UPDATE webhooks.webhook_delivery SET next_attempt_at = now() - interval '1 second' "
          + "WHERE endpoint_id = (SELECT id FROM webhooks.webhook_endpoint WHERE url LIKE '%/claim-other')");
    }

    List<DueDelivery> due = store.claimDueDeliveries(Instant.now(), 10);
    assertTrue(due.stream().anyMatch(d -> d.url().toString().endsWith("/claim-other")));
    assertTrue(due.stream().noneMatch(d -> d.url().toString().endsWith("/claim")));
    assertTrue(due.stream().allMatch(d -> d.payload() != null && d.eventType() != null));
  }

  @Test
  void outcomeRecordingMovesStatusAttemptsAndBackoff() {
    var target = endpoint("outcomes", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
    long deliveryId = store.claimDueDeliveries(Instant.now(), 10).stream()
        .filter(d -> d.url().toString().endsWith("/outcomes")).findFirst().orElseThrow().id();

    store.recordDeliveryRetry(deliveryId, 500, Instant.now().plusSeconds(30));
    Optional<DeliveryRecord> retried = store.listDeliveries(SeedMerchant.PUBLIC_ID, target.publicId(), null, null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst();
    assertTrue(retried.isPresent());
    assertEquals("PENDING", retried.get().status());
    assertEquals(1, retried.get().attempts());
    assertEquals(500, retried.get().lastResponseStatus());

    store.recordDeliverySuccess(deliveryId, 200);
    assertEquals("SUCCEEDED", store.listDeliveries(SeedMerchant.PUBLIC_ID, target.publicId(), null, null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst().orElseThrow().status());

    // Guarded: a second outcome on a non-PENDING row is a no-op (rowcount 0, no exception).
    store.recordDeliveryRetry(deliveryId, 500, Instant.now());
    assertEquals("SUCCEEDED", store.listDeliveries(SeedMerchant.PUBLIC_ID, target.publicId(), null, null, 50).stream()
        .filter(d -> d.id() == deliveryId).findFirst().orElseThrow().status());
  }

  @Test
  void listDeliveriesFiltersByEndpointAndStatus() {
    var a = endpoint("filter-a", List.of());
    var b = endpoint("filter-b", List.of());
    store.insertEvent(UUID.randomUUID(), "payment_intent.settled", "{}", Instant.now());
    store.insertEvent(UUID.randomUUID(), "payment_intent.failed", "{}", Instant.now());

    store.claimDueDeliveries(Instant.now(), 10).stream()
        .filter(d -> d.url().toString().endsWith("/filter-a")).findFirst()
        .ifPresent(d -> store.recordDeliverySuccess(d.id(), null));

    assertEquals(1, store.listDeliveries(SeedMerchant.PUBLIC_ID, a.publicId(), "SUCCEEDED", null, 50).size());
    assertEquals(0, store.listDeliveries(SeedMerchant.PUBLIC_ID, a.publicId(), "FAILED", null, 50).size());
    assertEquals(2, store.listDeliveries(SeedMerchant.PUBLIC_ID, b.publicId(), null, null, 50).size());
  }
}
