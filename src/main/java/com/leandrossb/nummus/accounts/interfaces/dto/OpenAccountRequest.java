package com.leandrossb.nummus.accounts.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for opening a payment account. */
public record OpenAccountRequest(
    @NotBlank(message = "holderName must not be blank")
    @Size(max = 200, message = "holderName must be at most 200 characters")
    String holderName) {
}
