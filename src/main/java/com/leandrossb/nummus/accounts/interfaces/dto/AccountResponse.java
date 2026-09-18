package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import java.time.Instant;
import java.util.UUID;

/** REST view of a payment account. UUIDs only — internal ids never appear. */
public record AccountResponse(
    UUID publicId,
    String holderName,
    String status,
    Instant openedAt,
    Instant closedAt) {

  public static AccountResponse from(PaymentAccount account) {
    return new AccountResponse(account.publicId(), account.holderName(),
        account.status().name(), account.openedAt(), account.closedAt());
  }
}
