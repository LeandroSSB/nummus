package com.leandrossb.nummus.merchants.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Key rotation policy: the calling key keeps working for the grace window
 *  after a rotation, then hard-fails 401. Rotation never extends an existing
 *  nearer expiry. */
@ConfigurationProperties(prefix = "nummus.api-keys")
public record ApiKeyProperties(@DefaultValue("PT5M") Duration rotationGrace) {
}
