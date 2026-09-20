package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request body for PUT /v1/merchants/{id}/fee. Mirrors {@link FeeRequest}'s
 *  validations — the two stay in lockstep so create and update accept the
 *  same schedules. */
public record UpdateFeeRequest(
    @NotNull
    @DecimalMin(value = "0", message = "rate must be >= 0")
    @DecimalMax(value = "1", inclusive = false, message = "rate must be < 1")
    @Digits(integer = 0, fraction = 6) BigDecimal rate,
    @NotNull
    @DecimalMin(value = "0", message = "fixedAmount must be >= 0")
    @Digits(integer = 15, fraction = 4) BigDecimal fixedAmount) {
}
