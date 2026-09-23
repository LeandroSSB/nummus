package com.leandrossb.nummus.payments.application;

/** Charge state as seen at the external payment network. */
public enum ChargeStatus {
  PENDING,
  SUCCEEDED,
  FAILED,
  /** Terminal withdrawal of an in-flight instruction before it executed —
   * reached only by the expiry resolver or an operator cancel; charges never
   * enter this state. */
  CANCELLED
}
