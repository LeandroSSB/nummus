package com.leandrossb.nummus.merchants.interfaces.dto;

import java.time.Duration;

/** Optional mint body for operator keys: label is the key's immutable
 *  identity (required, 1-64 characters); expiresIn is an ISO-8601 duration,
 *  omitted or null meaning the key never expires. */
public record CreateKeyRequest(String label, Duration expiresIn) {
}
