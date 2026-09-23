package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for requesting a payout. Scale is validated by Money (400 on breach). */
public record CreatePayoutRequest(
    @NotNull(message = "accountId must not be null") UUID accountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    @Digits(integer = 15, fraction = 4,
        message = "amount must fit numeric(19,4): at most 15 integer and 4 fraction digits") BigDecimal amount,
    @NotBlank(message = "destinationBankKey must not be blank")
    @Pattern(regexp = "[A-Za-z0-9._-]+",
        message = "destinationBankKey must match [A-Za-z0-9._-]+")
    @Size(max = 64, message = "destinationBankKey must be at most 64 characters") String destinationBankKey,
    @Min(value = 60, message = "expiresInSeconds must be at least 60")
    @Max(value = 86400, message = "expiresInSeconds must be at most 86400") Long expiresInSeconds) {
}
