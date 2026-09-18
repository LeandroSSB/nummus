package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Retention window for stored responses. After expiry a key is reclaimed and re-executed. */
@ConfigurationProperties(prefix = "nummus.idempotency")
public record IdempotencyProperties(@DefaultValue("24h") Duration ttl) {
}
