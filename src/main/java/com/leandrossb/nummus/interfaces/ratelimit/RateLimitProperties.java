package com.leandrossb.nummus.interfaces.ratelimit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-tenant throttling: merchant requests bucket by merchant id, operator
 * requests by operator key id. Defaults: a merchant bursts 600 requests then
 * sustains 10/s; an operator key bursts 120 then sustains 2/s. Single-process
 * state — a restart resets buckets (documented bound).
 */
@ConfigurationProperties(prefix = "nummus.ratelimit")
@Validated
public record RateLimitProperties(
    @DefaultValue("600") @Min(1) int merchantCapacity,
    @DefaultValue("10") @Min(0) int merchantRefillPerSecond,
    @DefaultValue("120") @Min(1) int operatorCapacity,
    @DefaultValue("2") @Min(0) int operatorRefillPerSecond) {
}
