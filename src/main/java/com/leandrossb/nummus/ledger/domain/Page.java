package com.leandrossb.nummus.ledger.domain;

/** Offset-based pagination for statements. */
public record Page(int offset, int limit) {

  public Page {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0: " + offset);
    }
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("limit must be between 1 and 500: " + limit);
    }
  }
}
