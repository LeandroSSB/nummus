package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.ledger.domain.Money;

/** What an intent currently exposes as its fee and net: the settled fact once
 *  settled, the live schedule's estimate before that. */
public record FeeQuote(Money fee, Money netAmount) {
}
