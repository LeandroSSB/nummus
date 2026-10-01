package com.leandrossb.nummus.payments.application;

/** Charge state as seen at the external payment network. */
public enum ChargeStatus {
  PENDING,
  SUCCEEDED,
  FAILED,
  /** Terminal withdrawal of an in-flight instruction before it executed —
   * reached by the expiry resolver, an operator cancel, or a merchant voiding
   * the intent before the payer pays. */
  CANCELLED
}
