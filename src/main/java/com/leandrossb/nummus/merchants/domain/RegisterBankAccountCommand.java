package com.leandrossb.nummus.merchants.domain;

/** Command to register a payout destination. Shapes are validated at the
 *  REST boundary; the tax-id check digits are validated in the service. */
public record RegisterBankAccountCommand(String bankCode, String branch, String accountNumber,
    String holderTaxId) {

  public RegisterBankAccountCommand withTaxId(String taxId) {
    return new RegisterBankAccountCommand(bankCode, branch, accountNumber, taxId);
  }
}
