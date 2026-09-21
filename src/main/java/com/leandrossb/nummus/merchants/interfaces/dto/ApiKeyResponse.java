package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Instant;
import java.util.UUID;

/** Key listing view — prefix only, never the secret. */
public record ApiKeyResponse(UUID keyId, String prefix, String status, Instant createdAt,
    Instant expiresAt, Instant lastUsedAt) {

  public static ApiKeyResponse from(ApiKey key) {
    return new ApiKeyResponse(key.publicId(), key.prefix(), key.status(), key.createdAt(),
        key.expiresAt(), key.lastUsedAt());
  }
}
