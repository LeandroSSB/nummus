package com.leandrossb.nummus.merchants.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The deployment token that mints the first operator key
 * ({@code nummus.operator.bootstrap-token}). Null when unset, in which case
 * the bootstrap path is unavailable.
 */
@ConfigurationProperties(prefix = "nummus.operator")
public record OperatorBootstrapProperties(String bootstrapToken) {
}
