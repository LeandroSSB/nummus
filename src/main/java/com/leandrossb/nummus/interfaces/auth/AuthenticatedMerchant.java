package com.leandrossb.nummus.interfaces.auth;

import java.util.UUID;

/** The authenticated caller, resolved from a Bearer API key. Shared vocabulary:
 *  controllers declare this parameter to mark a merchant route. */
public record AuthenticatedMerchant(UUID merchantPublicId, String name) {
}
