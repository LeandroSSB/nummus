package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.IssuedBankAccount;
import java.time.Instant;
import java.util.UUID;

/** Creation responses carry the verification code exactly once. */
public record IssuedBankAccountResponse(
    UUID bankAccountId, String bankCode, String branch, String accountNumber,
    String holderTaxId, String status, Instant createdAt, String verificationCode) {

  public static IssuedBankAccountResponse from(IssuedBankAccount issued) {
    var account = issued.account();
    return new IssuedBankAccountResponse(account.publicId(), account.bankCode(),
        account.branch(), account.accountNumber(), account.holderTaxId(), account.status(),
        account.createdAt(), issued.verificationCode());
  }
}
