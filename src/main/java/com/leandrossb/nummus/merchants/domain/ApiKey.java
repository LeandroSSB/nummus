package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** An API key's metadata. The secret exists only at issuance; the prefix is
 *  display-safe. expiresAt is null when the key never expires; lastUsedAt is
 *  null until the first successful authentication. */
public record ApiKey(UUID publicId, String prefix, String status, Instant createdAt,
    Instant expiresAt, Instant lastUsedAt) {
}
