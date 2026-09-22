package com.leandrossb.nummus.merchants.application;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Key rotation policy: the calling key keeps working for the grace window
 *  after a rotation, then hard-fails 401. Rotation never extends an existing
 *  nearer expiry. */
@ConfigurationProperties(prefix = "nummus.api-keys")
@Validated
public record ApiKeyProperties(
    @DefaultValue("PT5M") @DurationMin(nanos = 0, inclusive = false) Duration rotationGrace) {
}
