package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** One persisted verdict. INTERNAL lines (MISSING_EXTERNAL) carry a null reportedAmount. */
public record MatchedLine(String origin, UUID chargePublicId, Money reportedAmount,
    UUID internalIntentPublicId, Money internalAmount, String matchStatus) {
}
