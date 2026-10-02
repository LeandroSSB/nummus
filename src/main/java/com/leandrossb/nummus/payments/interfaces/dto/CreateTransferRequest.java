package com.leandrossb.nummus.payments.interfaces.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for moving booked funds between two of the merchant's own
 *  payment accounts. Scale is validated by Money (400 on breach). */
public record CreateTransferRequest(
    @NotNull(message = "fromAccountId must not be null") UUID fromAccountId,
    @NotNull(message = "toAccountId must not be null") UUID toAccountId,
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.0001", message = "amount must be positive")
    @Digits(integer = 15, fraction = 4,
        message = "amount must fit numeric(19,4): at most 15 integer and 4 fraction digits") BigDecimal amount) {
}
