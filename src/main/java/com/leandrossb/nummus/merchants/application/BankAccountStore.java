package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the bank-account registry. Transitions are
 *  status-guarded; identity fields are write-once. */
public interface BankAccountStore {

  BankAccount insert(BankAccount account, String verificationCodeHash);

  Optional<BankAccount> findByPublicIdAndMerchant(UUID merchantPublicId, UUID publicId);

  List<BankAccount> listByMerchant(UUID merchantPublicId, int limit);

  /** Verifies in one guarded statement: wins only while the row is
   *  PENDING_VERIFICATION and the hash matches. */
  boolean verify(UUID merchantPublicId, UUID publicId, String verificationCodeHash);

  /** Revokes in one guarded statement: wins from PENDING_VERIFICATION or
   *  VERIFIED. */
  boolean revoke(UUID merchantPublicId, UUID publicId);
}
