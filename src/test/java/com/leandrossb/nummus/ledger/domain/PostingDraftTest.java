package com.leandrossb.nummus.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingDraftTest {

  @Test
  void requiresStrictlyPositiveAmount() {
    assertThrows(InvalidMoneyException.class,
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("0.0000")));
    assertThrows(InvalidMoneyException.class,
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("-1.0000")));
    assertDoesNotThrow(
        () -> new PostingDraft(UUID.randomUUID(), Direction.DEBIT, Money.ofBrl("0.0001")));
  }

  @Test
  void requiresAccountAndDirection() {
    var amount = Money.ofBrl("1.0000");
    assertThrows(NullPointerException.class, () -> new PostingDraft(null, Direction.DEBIT, amount));
    assertThrows(NullPointerException.class,
        () -> new PostingDraft(UUID.randomUUID(), null, amount));
  }
}
