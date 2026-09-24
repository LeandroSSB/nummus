package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for requesting a payout. Scale is validated by Money (400 on breach). */
public record CreatePayoutRequest(
    @NotNull(message = "accountId must not be null") UUID accountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    @Digits(integer = 15, fraction = 4,
        message = "amount must fit numeric(19,4): at most 15 integer and 4 fraction digits") BigDecimal amount,
    @NotNull(message = "bankAccountId must not be null") UUID bankAccountId,
    @Min(value = 60, message = "expiresInSeconds must be at least 60")
    @Max(value = 86400, message = "expiresInSeconds must be at most 86400") Long expiresInSeconds) {
}
