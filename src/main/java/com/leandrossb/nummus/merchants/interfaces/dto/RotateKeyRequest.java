package com.leandrossb.nummus.merchants.interfaces.dto;

import java.time.Duration;

/** Optional rotation body: expiresIn applies to the NEW key. */
public record RotateKeyRequest(Duration expiresIn) {
}
