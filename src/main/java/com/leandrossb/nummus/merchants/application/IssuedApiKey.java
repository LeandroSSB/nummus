package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.ApiKey;

/** A freshly minted key: the ONLY time the secret is visible. */
public record IssuedApiKey(ApiKey key, String secret) {
}
