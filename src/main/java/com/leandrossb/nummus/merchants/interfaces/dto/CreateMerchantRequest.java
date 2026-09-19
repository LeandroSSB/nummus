package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateMerchantRequest(@NotBlank(message = "name must not be blank") String name) {
}
