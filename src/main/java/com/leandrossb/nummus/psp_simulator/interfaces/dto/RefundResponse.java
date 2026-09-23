package com.leandrossb.nummus.psp_simulator.interfaces.dto;

import com.leandrossb.nummus.payments.application.NetworkRefund;
import java.math.BigDecimal;
import java.util.UUID;

/** REST view of a simulated charge refund. */
public record RefundResponse(UUID publicId, UUID chargePublicId, BigDecimal amount, String currency,
    String status) {

  public static RefundResponse from(NetworkRefund refund) {
    return new RefundResponse(refund.publicId(), refund.chargePublicId(),
        refund.amount().amount(), refund.amount().currency().getCurrencyCode(),
        refund.status().name());
  }
}
