package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for registering a payout destination. Shapes only — the
 *  tax-id check digits are validated in the service (400 either way). */
public record RegisterBankAccountRequest(
    @NotBlank(message = "bankCode must not be blank")
    @Pattern(regexp = "\\d{3}", message = "bankCode must be exactly 3 digits") String bankCode,
    @NotBlank(message = "branch must not be blank")
    @Pattern(regexp = "\\d{1,5}(-\\d)?",
        message = "branch must be 1-5 digits with an optional check digit") String branch,
    @NotBlank(message = "accountNumber must not be blank")
    @Pattern(regexp = "[0-9-]{1,20}",
        message = "accountNumber must be 1-20 digits or dashes") String accountNumber,
    @NotBlank(message = "holderTaxId must not be blank")
    @Pattern(regexp = "\\d{11}|\\d{14}",
        message = "holderTaxId must be 11 (CPF) or 14 (CNPJ) digits") String holderTaxId) {
}
