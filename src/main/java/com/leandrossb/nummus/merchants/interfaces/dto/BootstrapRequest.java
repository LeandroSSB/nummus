package com.leandrossb.nummus.merchants.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** The deployment token that mints the first operator key, plus the label of
 *  the identity it mints (validated in the service, before any state check). */
public record BootstrapRequest(@NotBlank(message = "token must not be blank") String token,
    String label) {
}
