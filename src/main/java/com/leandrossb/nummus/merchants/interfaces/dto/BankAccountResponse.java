package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.time.Instant;
import java.util.UUID;

/** REST view of a registered account. The verification code appears ONLY on
 *  the creation response — never here. */
public record BankAccountResponse(
    UUID bankAccountId, String bankCode, String branch, String accountNumber,
    String holderTaxId, String status, Instant createdAt, Instant verifiedAt) {

  public static BankAccountResponse from(BankAccount account) {
    return new BankAccountResponse(account.publicId(), account.bankCode(), account.branch(),
        account.accountNumber(), account.holderTaxId(), account.status(), account.createdAt(),
        account.verifiedAt());
  }
}
