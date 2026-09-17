package com.leandrossb.nummus.ledger.domain;

import java.util.UUID;

/** A posting as persisted in the journal. */
public record PostedPosting(UUID accountPublicId, Direction direction, Money amount) {
}
