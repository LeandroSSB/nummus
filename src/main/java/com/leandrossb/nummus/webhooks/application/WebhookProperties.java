package com.leandrossb.nummus.webhooks.application;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Delivery retry policy: 8 attempts, delays backoffBase x 2^attempt (4s, 8s, ... 256s at PT2S).
 * Retention: SUCCEEDED deliveries older than retentionDays are pruned; 0 disables pruning. */
@ConfigurationProperties(prefix = "nummus.webhooks")
@Validated
public record WebhookProperties(
    @DefaultValue("8") @Min(1) int maxAttempts,
    @DefaultValue("PT2S") @DurationMin(nanos = 0, inclusive = false) Duration backoffBase,
    @DefaultValue("50") @Min(1) int batchSize,
    @DefaultValue("30") @Min(0) int retentionDays) {
}
