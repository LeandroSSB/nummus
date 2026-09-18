package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the outbox. {@link #insertEvent} fans out inside the
 * caller's transaction: one delivery row per ACTIVE endpoint whose
 * event_types is empty (all types) or contains the event's type. Outcome
 * methods are guarded on status = 'PENDING' and stamp last_attempt_at with
 * the database clock.
 */
public interface WebhookStore {

  WebhookEndpoint insertEndpoint(WebhookEndpoint endpoint);

  List<WebhookEndpoint> listActiveEndpoints();

  Optional<WebhookEndpoint> findActiveEndpoint(UUID publicId);

  boolean markEndpointDeleted(UUID publicId);

  void insertEvent(UUID eventPublicId, String type, String payload, Instant occurredAt);

  List<DueDelivery> claimDueDeliveries(Instant now, int limit);

  void recordDeliverySuccess(long deliveryId, Integer responseStatus);

  void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt);

  void recordDeliveryFailure(long deliveryId, Integer responseStatus);

  List<DeliveryRecord> listDeliveries(UUID endpointPublicId, String status, int limit);
}
