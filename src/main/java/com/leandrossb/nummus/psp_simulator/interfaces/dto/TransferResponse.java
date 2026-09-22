package com.leandrossb.nummus.psp_simulator.interfaces.dto;

import com.leandrossb.nummus.payments.application.NetworkTransfer;
import java.math.BigDecimal;
import java.util.UUID;

/** REST view of a simulated outbound transfer. */
public record TransferResponse(UUID publicId, BigDecimal amount, String currency, String status,
    String destinationBankKey) {

  public static TransferResponse from(NetworkTransfer transfer) {
    return new TransferResponse(transfer.publicId(), transfer.amount().amount(),
        transfer.amount().currency().getCurrencyCode(), transfer.status().name(),
        transfer.destinationBankKey());
  }
}
