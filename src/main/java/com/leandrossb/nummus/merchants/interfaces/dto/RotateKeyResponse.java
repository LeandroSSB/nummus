package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.RotatedApiKey;
import java.time.Instant;
import java.util.UUID;

/** Rotation responses carry the new secret exactly once, plus the moment the
 *  calling key stops working. */
public record RotateKeyResponse(UUID keyId, String prefix, String status, Instant createdAt,
    Instant expiresAt, String secret, Instant oldKeyExpiresAt) {

  public static RotateKeyResponse from(RotatedApiKey rotated) {
    return new RotateKeyResponse(rotated.issued().key().publicId(),
        rotated.issued().key().prefix(), rotated.issued().key().status(),
        rotated.issued().key().createdAt(), rotated.issued().key().expiresAt(),
        rotated.issued().secret(), rotated.oldKeyExpiresAt());
  }
}
