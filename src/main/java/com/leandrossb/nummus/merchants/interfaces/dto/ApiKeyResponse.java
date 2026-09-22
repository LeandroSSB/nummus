package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.domain.ApiKey;
import java.time.Instant;
import java.util.UUID;

/** Key listing view — prefix only, never the secret. label is the operator
 *  key's audit identity; null for merchant keys. */
public record ApiKeyResponse(UUID keyId, String prefix, String status, Instant createdAt,
    Instant expiresAt, Instant lastUsedAt, String label) {

  public static ApiKeyResponse from(ApiKey key) {
    return new ApiKeyResponse(key.publicId(), key.prefix(), key.status(), key.createdAt(),
        key.expiresAt(), key.lastUsedAt(), key.label());
  }
}
