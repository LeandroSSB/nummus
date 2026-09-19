package com.leandrossb.nummus.merchants.domain;

import java.time.Instant;
import java.util.UUID;

/** The owning identity of accounts, webhook endpoints, and idempotency namespaces. */
public record Merchant(UUID publicId, String name, Instant createdAt) {
}
