package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.Payout;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module's payouts. Every state change is status-guarded. */
public interface PayoutsRepository {

  Payout insert(Payout payout);

  Optional<Payout> findByPublicId(UUID publicId);
}
