package com.leandrossb.nummus.webhooks.application;

import com.leandrossb.nummus.payments.application.IntentEventTypes;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException;
import com.leandrossb.nummus.webhooks.domain.WebhookEndpoint;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Subscription lifecycle. The signing secret is generated here and shown to callers exactly once. */
@Service
public class WebhookEndpointsService {

  private final WebhookStore store;
  private final SecureRandom random = new SecureRandom();

  public WebhookEndpointsService(WebhookStore store) {
    this.store = store;
  }

  public WebhookEndpoint register(URI url, List<String> eventTypes) {
    List<String> types = eventTypes == null ? List.of() : eventTypes;
    List<String> unknown = types.stream().filter(t -> !IntentEventTypes.ALL.contains(t)).toList();
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException("unknown event types: " + unknown);
    }
    byte[] secretBytes = new byte[32];
    random.nextBytes(secretBytes);
    String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    return store.insertEndpoint(new WebhookEndpoint(UUID.randomUUID(), url, secret,
        types, com.leandrossb.nummus.webhooks.domain.EndpointStatus.ACTIVE, Instant.now()));
  }

  public List<WebhookEndpoint> list() {
    return store.listActiveEndpoints();
  }

  public WebhookEndpoint get(UUID publicId) {
    return store.findActiveEndpoint(publicId).orElseThrow(() -> new UnknownWebhookEndpointException(publicId));
  }

  public void delete(UUID publicId) {
    if (!store.markEndpointDeleted(publicId)) {
      throw new UnknownWebhookEndpointException(publicId);
    }
  }
}
