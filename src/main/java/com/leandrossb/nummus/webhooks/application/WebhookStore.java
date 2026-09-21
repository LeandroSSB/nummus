package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the outbox. Endpoint registration and lookup are
 * scoped to the owning merchant — another merchant's endpoint is
 * indistinguishable from an unknown one. {@link #insertEvent} fans out
 * inside the caller's transaction to every ACTIVE endpoint in the
 * audience: one delivery row per endpoint whose event_types is empty (all
 * types) or contains the event's type. Outcome methods are guarded on
 * status = 'PENDING' and stamp last_attempt_at with the database clock.
 */
public interface WebhookStore {

  WebhookEndpoint insertEndpoint(WebhookEndpoint endpoint);

  List<WebhookEndpoint> listActiveEndpoints(UUID merchantPublicId);

  Optional<WebhookEndpoint> findActiveEndpoint(UUID merchantPublicId, UUID publicId);

  boolean markEndpointDeleted(UUID merchantPublicId, UUID publicId);

  /** Fans out inside the caller's transaction to every ACTIVE endpoint in the
   * audience: the event's merchant, or — when audienceMerchant is null — the
   * operator namespace (merchant_public_id is null). One delivery row per
   * endpoint whose event_types is empty (all types) or contains the type. */
  void insertEvent(UUID eventPublicId, UUID audienceMerchant, String type, String payload,
      Instant occurredAt);

  List<DueDelivery> claimDueDeliveries(Instant now, int limit);

  void recordDeliverySuccess(long deliveryId, Integer responseStatus);

  void recordDeliveryRetry(long deliveryId, Integer responseStatus, Instant nextAttemptAt);

  void recordDeliveryFailure(long deliveryId, Integer responseStatus);

  boolean requeueFailedDelivery(UUID merchantPublicId, UUID deliveryPublicId);

  List<DeliveryRecord> listDeliveries(UUID merchantPublicId, UUID endpointPublicId, String status,
      UUID after, int limit);

  /** Deletes SUCCEEDED deliveries with last_attempt_at before the cutoff, in
   * batch-sized chunks; returns the total deleted. FAILED and PENDING rows are
   * never touched. */
  int pruneSucceededBefore(Instant cutoff, int batch);
}
