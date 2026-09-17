package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageTest {

  @Test
  void validatesOffsetAndLimit() {
    assertDoesNotThrow(() -> new Page(0, 1));
    assertDoesNotThrow(() -> new Page(10_000, 500));
    assertThrows(IllegalArgumentException.class, () -> new Page(-1, 10));
    assertThrows(IllegalArgumentException.class, () -> new Page(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Page(0, 501));
  }
}
