package com.leandrossb.nummus.webhooks.application;

import java.net.URI;

/** The outbound HTTP boundary, isolated as a port so the retry policy is testable without a server. */
public interface EventDeliveryClient {

  DeliveryResult deliver(URI url, String secret, String eventType, String payload);
}
