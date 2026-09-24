package com.leandrossb.nummus.merchants.application;

import java.util.UUID;

/** What a payout needs from a verified destination: the account it references
 *  and the wire key derived from the structured fields. The registry's other
 *  data (tax id, hashes) never crosses this port. */
public record PayoutDestination(UUID bankAccountPublicId, String wireKey) {
}
