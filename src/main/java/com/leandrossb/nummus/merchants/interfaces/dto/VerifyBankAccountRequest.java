package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyBankAccountRequest(
    @NotBlank(message = "code must not be blank") String code) {
}
