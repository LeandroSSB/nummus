package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.payments.domain.PaymentIntent;
import java.util.UUID;

/**
 * The payments module's internal API. Reads apply lazy expiry then lazy settlement;
 * settlement is exactly-once and posts the balanced clearing/payable entry.
 */
public interface PaymentsService {

  PaymentIntent create(CreateIntentCommand cmd);

  /** Applies lazy expiry and lazy settlement, then returns the current state. */
  PaymentIntent get(UUID publicId);
}
