package com.leandrossb.nummus.interfaces.auth;

import java.util.UUID;

/** The authenticated caller, resolved from a Bearer API key. Shared vocabulary:
 *  controllers declare this parameter to mark a merchant route. keyPublicId
 *  identifies the calling key — rotation targets it. */
public record AuthenticatedMerchant(UUID merchantPublicId, String name, UUID keyPublicId) {
}
