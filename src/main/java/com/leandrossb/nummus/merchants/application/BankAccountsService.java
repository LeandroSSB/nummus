package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The bank-account registry: a merchant's registered payout destinations. */
public interface BankAccountsService {

  /** Registers a destination (PENDING_VERIFICATION) and returns the one-time
   *  verification code. Tax-id check digits are validated here — the REST
   *  layer checks shapes only. */
  IssuedBankAccount register(UUID merchantPublicId, RegisterBankAccountCommand cmd);

  /** The merchant's account; empty for unknown or foreign ids. */
  Optional<BankAccount> find(UUID merchantPublicId, UUID publicId);

  /** The merchant's accounts, most recent first (bounded). */
  List<BankAccount> list(UUID merchantPublicId);

  /** Redeems the one-time code: PENDING_VERIFICATION → VERIFIED. */
  BankAccount verify(UUID merchantPublicId, UUID publicId, String rawCode);

  /** Soft-deletes: → REVOKED (terminal). */
  void revoke(UUID merchantPublicId, UUID publicId);

  /** Resolves a payout destination: the account must exist for this merchant
   *  and be VERIFIED. @throws UnknownBankAccountException unknown or foreign
   *  id; @throws BankAccountNotVerifiedException not yet verified (or revoked). */
  PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId);
}
