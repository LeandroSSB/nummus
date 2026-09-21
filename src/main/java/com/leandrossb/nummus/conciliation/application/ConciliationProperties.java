package com.leandrossb.nummus.conciliation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Scheduled-ingest policy: tumbling windows every poll delay, the window
 *  end held back by the lag so the M6 clock-skew boundary never straddles a
 *  window edge (a straddle would otherwise leave a permanent spurious
 *  MISSING pair no re-ingest can heal). */
@ConfigurationProperties(prefix = "nummus.conciliation")
public record ConciliationProperties(
    @DefaultValue("300000") long pollDelayMs,
    @DefaultValue("60000") long initialDelayMs,
    @DefaultValue("PT30S") Duration windowLag) {
}
