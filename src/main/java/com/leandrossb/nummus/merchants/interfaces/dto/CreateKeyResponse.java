package com.leandrossb.nummus.merchants.interfaces.dto;

import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import java.time.Instant;
import java.util.UUID;

/** Create responses carry the secret exactly once. */
public record CreateKeyResponse(UUID keyId, String prefix, String status,
    Instant createdAt, String secret) {

  public static CreateKeyResponse from(IssuedApiKey issued) {
    return new CreateKeyResponse(issued.key().publicId(), issued.key().prefix(),
        issued.key().status(), issued.key().createdAt(), issued.secret());
  }
}
