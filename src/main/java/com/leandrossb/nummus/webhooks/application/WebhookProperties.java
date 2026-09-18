package com.leandrossb.nummus.webhooks.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Delivery retry policy: 8 attempts, delays backoffBase x 2^attempt (4s, 8s, ... 256s at PT2S). */
@ConfigurationProperties(prefix = "nummus.webhooks")
public record WebhookProperties(
    @DefaultValue("8") int maxAttempts,
    @DefaultValue("PT2S") Duration backoffBase,
    @DefaultValue("50") int batchSize) {
}
