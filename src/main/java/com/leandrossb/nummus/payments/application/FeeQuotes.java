package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FeeQuotes {

  private final MerchantsService merchants;

  public FeeQuotes(MerchantsService merchants) {
    this.merchants = merchants;
  }

  /** Settled intents carry the charged fact; open intents quote the rate in force. */
  public FeeQuote quoteFor(UUID merchantPublicId, PaymentIntent intent) {
    if (intent.feeAmount() != null) {
      return new FeeQuote(intent.feeAmount(), intent.amount().subtract(intent.feeAmount()));
    }
    var current = FeeCalculator.compute(intent.amount(),
        merchants.findFeeSchedule(merchantPublicId).orElse(FeeSchedule.ZERO));
    return new FeeQuote(current.fee(), current.net());
  }
}
