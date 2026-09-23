package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** One persisted verdict. INTERNAL lines (MISSING_EXTERNAL) carry a null reportedAmount. */
public record MatchedLine(String origin, SubjectType subjectType, UUID subjectPublicId,
    Money reportedAmount, UUID internalPublicId, Money internalAmount, String matchStatus) {
}
