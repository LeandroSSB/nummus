package com.leandrossb.nummus.interfaces.idempotency;

import java.time.Instant;

/**
 * A key's stored state. {@code response} is null until the owning transaction
 * attaches it; a committed row always carries its response because the
 * reservation, the business write, and the attachment commit together.
 */
public record StoredRow(byte[] requestFingerprint, Instant expiresAt, StoredResponse response) {
}
