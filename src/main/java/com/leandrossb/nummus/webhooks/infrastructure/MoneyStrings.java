package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.ledger.domain.Money;

/**
 * Merchant-facing money strings shared by the lifecycle outbox adapters: at
 * least centavos, never spurious trailing zeros (a journal-scale
 * {@code 98.6200} publishes as {@code 98.62}). Lossless — only exact trailing
 * zeros are dropped. Null-friendly: absent fee facts publish as absent keys,
 * never as {@code "null"}.
 */
final class MoneyStrings {

  static String toMoneyString(Money money) {
    if (money == null) {
      return null;
    }
    var stripped = money.amount().stripTrailingZeros();
    return stripped.setScale(Math.max(2, stripped.scale())).toPlainString();
  }

  private MoneyStrings() {
  }
}
