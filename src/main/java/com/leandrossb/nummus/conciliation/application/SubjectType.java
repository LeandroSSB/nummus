package com.leandrossb.nummus.conciliation.application;

/** What a report line reconciles: money-in charges, or the two money-out
 *  instruction kinds. The report's subject dimension. */
public enum SubjectType {
  CHARGE, PAYOUT_TRANSFER, CHARGE_REFUND
}
