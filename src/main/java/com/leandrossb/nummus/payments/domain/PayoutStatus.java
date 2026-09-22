package com.leandrossb.nummus.payments.domain;

/** Payout lifecycle: REQUESTED is the only mutable state; the rest are terminal. */
public enum PayoutStatus {
  REQUESTED,
  SETTLED,
  FAILED,
  EXPIRED
}
