package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** Quotes for a page: settled intents carry their charged fact; open ones
   *  are priced by one schedule sweep, not one lookup per row. */
  public Map<UUID, FeeQuote> quotesFor(UUID merchantPublicId, List<PaymentIntent> intents) {
    if (intents.isEmpty()) {
      return Map.of();
    }
    FeeSchedule schedule = null;
    var result = new HashMap<UUID, FeeQuote>(intents.size());
    for (PaymentIntent intent : intents) {
      if (intent.feeAmount() != null) {
        result.put(intent.publicId(),
            new FeeQuote(intent.feeAmount(), intent.amount().subtract(intent.feeAmount())));
      } else {
        if (schedule == null) {
          schedule = merchants.findFeeSchedule(merchantPublicId).orElse(FeeSchedule.ZERO);
        }
        var current = FeeCalculator.compute(intent.amount(), schedule);
        result.put(intent.publicId(), new FeeQuote(current.fee(), current.net()));
      }
    }
    return result;
  }
}
