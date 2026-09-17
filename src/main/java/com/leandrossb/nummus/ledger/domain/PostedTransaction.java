package com.leandrossb.nummus.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A committed journal transaction. {@code reversalOf} points at the original when this is a reversal. */
public record PostedTransaction(
    UUID publicId,
    String memo,
    Instant bookedAt,
    UUID reversalOf,
    List<PostedPosting> postings) {
}
