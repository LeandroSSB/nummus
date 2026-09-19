package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** The deployment token that mints the first operator key. */
public record BootstrapRequest(@NotBlank(message = "token must not be blank") String token) {
}
