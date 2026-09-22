package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import java.time.Instant;
import java.util.UUID;

/** Create responses carry the secret exactly once. label is the operator
 *  key's audit identity; null for merchant keys. */
public record CreateKeyResponse(UUID keyId, String prefix, String status,
    Instant createdAt, Instant expiresAt, String label, String secret) {

  public static CreateKeyResponse from(IssuedApiKey issued) {
    return new CreateKeyResponse(issued.key().publicId(), issued.key().prefix(),
        issued.key().status(), issued.key().createdAt(), issued.key().expiresAt(),
        issued.key().label(), issued.secret());
  }
}
