package com.leandrossb.nummus.payments.application;

import com.leandrossb.nummus.payments.domain.CreateTransferCommand;
import com.leandrossb.nummus.payments.domain.Transfer;
import java.util.List;
import java.util.UUID;

/**
 * The internal-transfer side of the payments module: instant book movement
 * between two of a merchant's own payment accounts. A transfer executes
 * synchronously as one balanced journal entry committed atomically with the
 * transfer row — there is no lifecycle to drive.
 */
public interface TransfersService {

  Transfer create(UUID merchantPublicId, CreateTransferCommand cmd);

  /** The merchant's transfer. A foreign transfer is indistinguishable from an
   *  unknown one. */
  Transfer get(UUID merchantPublicId, UUID publicId);

  /** The merchant's transfers, newest first, keyset-paginated. */
  List<Transfer> list(UUID merchantPublicId, UUID after, int limit);
}
