package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.audit.application.OperatorAudit;
import com.leandrossb.nummus.conciliation.application.ConciliationEventTypes;
import com.leandrossb.nummus.payments.application.IntentEventTypes;
import com.leandrossb.nummus.payments.application.PayoutEventTypes;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lifecycle, scoped to its audience — a merchant's endpoints or,
 * for the operator namespace, the shared NULL-audience set. Another
 * merchant's endpoint is indistinguishable from an unknown one. The signing
 * secret is generated here and shown to callers exactly once. Operator-namespace
 * mutations record an audit entry attributed to the calling key, inside the
 * mutation's own transaction; the merchant surface records nothing.
 */
@Service
public class WebhookEndpointsService {

  /** The merchant catalog: every lifecycle this audience can receive — the
   *  intent (money-in) and payout (money-out) event types together. */
  private static final Set<String> MERCHANT_EVENT_TYPES = Stream.concat(
      IntentEventTypes.ALL.stream(), PayoutEventTypes.ALL.stream())
      .collect(Collectors.toUnmodifiableSet());

  private final WebhookStore store;
  private final OperatorAudit audit;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointsService(WebhookStore store, OperatorAudit audit) {
    this.store = store;
    this.audit = audit;
  }

  public WebhookEndpoint register(UUID merchantPublicId, URI url, List<String> eventTypes) {
    return doRegister(merchantPublicId, url, eventTypes, MERCHANT_EVENT_TYPES);
  }

  /** Operator namespace (merchant_public_id NULL): conciliation alerts. The
   *  registration is attributed to the calling key, committed with it. */
  @Transactional
  public WebhookEndpoint registerOperator(URI url, List<String> eventTypes, UUID actorKey) {
    WebhookEndpoint endpoint = doRegister(null, url, eventTypes, ConciliationEventTypes.ALL);
    if (actorKey != null) {
      audit.record(actorKey, "operator_endpoint.registered", "webhook_endpoint",
          endpoint.publicId(), Map.of("url", url.toString()));
    }
    return endpoint;
  }

  private WebhookEndpoint doRegister(UUID merchantPublicId, URI url, List<String> eventTypes,
      Set<String> catalog) {
    WebhookUrlPolicy.check(url);
    List<String> types = eventTypes == null ? List.of() : eventTypes;
    List<String> unknown = types.stream().filter(t -> !catalog.contains(t)).toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown event types: " + unknown);
    }
    byte[] secretBytes = new byte[32];
    random.nextBytes(secretBytes);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    return store.insertEndpoint(new WebhookEndpoint(merchantPublicId, UUID.randomUUID(), url, secret,
        types, EndpointStatus.ACTIVE, Instant.now()));
  }

  public List<WebhookEndpoint> list(UUID merchantPublicId) {
    return store.listActiveEndpoints(merchantPublicId);
  }

  public WebhookEndpoint get(UUID merchantPublicId, UUID publicId) {
    return store.findActiveEndpoint(merchantPublicId, publicId)
        .orElseThrow(() -> new UnknownWebhookEndpointException(publicId));
  }

  public void delete(UUID merchantPublicId, UUID publicId) {
    if (!store.markEndpointDeleted(merchantPublicId, publicId)) {
      throw new UnknownWebhookEndpointException(publicId);
    }
  }

  /** Operator-namespace delete: soft, and attributed to the calling key. */
  @Transactional
  public void deleteOperator(UUID publicId, UUID actorKey) {
    if (!store.markEndpointDeleted(null, publicId)) {
      throw new UnknownWebhookEndpointException(publicId);
    }
    if (actorKey != null) {
      audit.record(actorKey, "operator_endpoint.deleted", "webhook_endpoint", publicId, Map.of());
    }
  }

  /** Operator-namespace redrive: the requeue and its audit entry commit
   *  together. The boolean keeps the caller's 404 semantics. */
  @Transactional
  public boolean redriveOperatorDelivery(UUID deliveryId, UUID actorKey) {
    boolean requeued = store.requeueFailedDelivery(null, deliveryId);
    if (requeued && actorKey != null) {
      audit.record(actorKey, "delivery.redriven", "webhook_delivery", deliveryId, Map.of());
    }
    return requeued;
  }
}
