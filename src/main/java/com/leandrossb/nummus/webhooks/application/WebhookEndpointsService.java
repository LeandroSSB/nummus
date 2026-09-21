package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.conciliation.application.ConciliationEventTypes;
import com.leandrossb.nummus.payments.application.IntentEventTypes;
import com.leandrossb.nummus.webhooks.domain.EndpointStatus;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Subscription lifecycle, scoped to its audience — a merchant's endpoints or,
 * for the operator namespace, the shared NULL-audience set. Another
 * merchant's endpoint is indistinguishable from an unknown one. The signing
 * secret is generated here and shown to callers exactly once.
 */
@Service
public class WebhookEndpointsService {

  private final WebhookStore store;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointsService(WebhookStore store) {
    this.store = store;
  }

  public WebhookEndpoint register(UUID merchantPublicId, URI url, List<String> eventTypes) {
    return doRegister(merchantPublicId, url, eventTypes, IntentEventTypes.ALL);
  }

  /** Operator namespace (merchant_public_id NULL): conciliation alerts. */
  public WebhookEndpoint registerOperator(URI url, List<String> eventTypes) {
    return doRegister(null, url, eventTypes, ConciliationEventTypes.ALL);
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
}
