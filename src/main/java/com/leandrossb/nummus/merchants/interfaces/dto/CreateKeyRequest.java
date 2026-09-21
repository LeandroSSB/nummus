package com.leandrossb.nummus.merchants.interfaces.dto;

import java.time.Duration;

/** Optional mint/rotation body: expiresIn is an ISO-8601 duration; omitted
 *  or null means the key never expires. */
public record CreateKeyRequest(Duration expiresIn) {
}
