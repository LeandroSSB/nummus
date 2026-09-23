package com.leandrossb.nummus.payments.domain;

/** Refund lifecycle: REQUESTED is the only mutable state; the rest are terminal. */
public enum RefundStatus {
  REQUESTED,
  SETTLED,
  FAILED,
  EXPIRED
}
