package com.leandrossb.nummus.conciliation.application;

/** Divergence tally; conciled iff every line matched. */
public record MatchSummary(int matched, int amountMismatched, int missingInternal,
    int missingExternal, boolean conciled) {
}
