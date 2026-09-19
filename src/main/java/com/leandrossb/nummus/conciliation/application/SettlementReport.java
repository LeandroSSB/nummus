package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;
import java.util.List;

/** The network's report for [from, to). */
public record SettlementReport(Instant from, Instant to, List<NetworkSettlement> lines) {
}
