package com.leandrossb.nummus.ledger.domain;

/** Account lifecycle: CLOSED is terminal; FROZEN and ACTIVE are reversible. */
public enum AccountStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
