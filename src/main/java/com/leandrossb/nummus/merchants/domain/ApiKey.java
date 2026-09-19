package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** An API key's metadata. The secret exists only at issuance; the prefix is display-safe. */
public record ApiKey(UUID publicId, String prefix, String status, Instant createdAt) {
}
