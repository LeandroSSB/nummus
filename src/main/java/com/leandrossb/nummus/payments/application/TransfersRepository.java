package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.Transfer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the payments module's internal transfers. */
public interface TransfersRepository {

  Transfer insert(Transfer transfer);

  Optional<Transfer> findByPublicId(UUID publicId);

  /** The merchant's transfers, newest first, keyset-paginated. */
  List<Transfer> listByMerchant(UUID merchantPublicId, UUID after, int limit);
}
