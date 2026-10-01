package com.leandrossb.nummus.payments.domain;

/** Payment intent lifecycle: CREATED is the only mutable state; the rest are
 *  terminal — EXPIRED is time's verdict, VOIDED the merchant's withdrawal. */
public enum IntentStatus {
  CREATED,
  SETTLED,
  FAILED,
  EXPIRED,
  VOIDED
}
