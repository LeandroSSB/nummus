package com.leandrossb.nummus.psp_simulator.interfaces.dto;

import com.leandrossb.nummus.payments.application.NetworkCharge;
import java.math.BigDecimal;
import java.util.UUID;

/** REST view of a simulated charge. */
public record ChargeResponse(UUID publicId, BigDecimal amount, String currency, String status) {

  public static ChargeResponse from(NetworkCharge charge) {
    return new ChargeResponse(charge.publicId(), charge.amount().amount(),
        charge.amount().currency().getCurrencyCode(), charge.status().name());
  }
}
