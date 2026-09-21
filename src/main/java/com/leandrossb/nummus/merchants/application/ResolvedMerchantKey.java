package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.Merchant;
import java.util.UUID;

/** The merchant behind a resolved key, plus the key's public id — rotation
 *  targets the calling key. */
public record ResolvedMerchantKey(Merchant merchant, UUID keyPublicId) {
}
