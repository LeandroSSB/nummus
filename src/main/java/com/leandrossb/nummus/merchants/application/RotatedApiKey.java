package com.leandrossb.nummus.merchants.application;

import java.time.Instant;

/** Result of a rotation: the freshly issued key and the moment the calling
 *  key stops working. */
public record RotatedApiKey(IssuedApiKey issued, Instant oldKeyExpiresAt) {
}
