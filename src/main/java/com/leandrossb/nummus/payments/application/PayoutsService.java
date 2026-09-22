package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.CreatePayoutCommand;
import com.leandrossb.nummus.payments.domain.Payout;
import java.util.UUID;

/**
 * The payout (money-out) side of the payments module. A request reserves the
 * funds in the journal under the account's row lock before the payout row
 * exists; execute and return settle or release that reservation.
 */
public interface PayoutsService {

  Payout create(UUID merchantPublicId, CreatePayoutCommand cmd);
}
