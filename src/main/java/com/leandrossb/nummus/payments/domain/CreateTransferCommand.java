package com.leandrossb.nummus.payments.domain;

import com.leandrossb.nummus.ledger.domain.Money;
import java.util.UUID;

/** Command to move booked funds between two of the merchant's own accounts.
 *  Both accounts must belong to the acting merchant and be ACTIVE, and the
 *  ids must differ. */
public record CreateTransferCommand(UUID fromAccountPublicId, UUID toAccountPublicId,
    Money amount) {
}
