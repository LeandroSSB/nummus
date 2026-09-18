package com.leandrossb.nummus.payments.domain;

/** Payment intent lifecycle: CREATED is the only mutable state; the rest are terminal. */
public enum IntentStatus {
  CREATED,
  SETTLED,
  FAILED,
  EXPIRED
}
