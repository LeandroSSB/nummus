package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** One internal settlement the matcher can pair with a report line, in the
 *  subject vocabulary — produced from each lifecycle's settled-window feed. */
public record InternalSettlement(
    SubjectType subjectType, UUID internalPublicId, UUID subjectPublicId, Money amount) {
}
