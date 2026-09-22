package com.leandrossb.nummus.conciliation.application;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Scheduled-ingest policy: tumbling windows every poll delay, the window
 *  end held back by the lag so the M6 clock-skew boundary never straddles a
 *  window edge (a straddle would otherwise leave a permanent spurious
 *  MISSING pair no re-ingest can heal). */
@ConfigurationProperties(prefix = "nummus.conciliation")
@Validated
public record ConciliationProperties(
    @DefaultValue("300000") @Min(1) long pollDelayMs,
    @DefaultValue("60000") @Min(1) long initialDelayMs,
    @DefaultValue("PT30S") @DurationMin(nanos = 0) Duration windowLag,
    @DefaultValue("PT5M") @DurationMin(nanos = 0, inclusive = false) Duration maxWindowAhead) {
}
