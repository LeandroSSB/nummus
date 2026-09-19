package com.leandrossb.nummus.conciliation.application;

import java.time.Instant;

/** Where settlement reports come from — the external payment network. */
public interface SettlementReportSource {

  SettlementReport fetch(Instant from, Instant to);
}
