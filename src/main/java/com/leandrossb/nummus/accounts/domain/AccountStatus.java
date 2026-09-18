package com.leandrossb.nummus.accounts.domain;

/** Payment account lifecycle: CLOSED is terminal; FROZEN and ACTIVE are reversible. */
public enum AccountStatus {
  ACTIVE,
  FROZEN,
  CLOSED
}
