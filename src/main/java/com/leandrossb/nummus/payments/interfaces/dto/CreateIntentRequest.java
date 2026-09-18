package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for creating a payment intent. Scale is validated by Money (400 on breach). */
public record CreateIntentRequest(
    @NotNull(message = "accountId must not be null") UUID accountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive") BigDecimal amount,
    @Min(value = 60, message = "expiresInSeconds must be at least 60")
    @Max(value = 86400, message = "expiresInSeconds must be at most 86400") Long expiresInSeconds) {
}
