package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fake arming verified destinations for service-level payout
 *  tests — the FakeMerchantsService precedent. */
public class FakeBankAccountsService implements BankAccountsService {

  private final Map<UUID, String> wireKeys = new ConcurrentHashMap<>();

  /** Arms a destination as VERIFIED with an exact wire key. */
  public void armVerified(UUID bankAccountPublicId, String wireKey) {
    wireKeys.put(bankAccountPublicId, wireKey);
  }

  @Override
  public PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId) {
    String wireKey = wireKeys.get(publicId);
    if (wireKey == null) {
      throw new UnknownBankAccountException(publicId);
    }
    return new PayoutDestination(publicId, wireKey);
  }

  @Override
  public IssuedBankAccount register(UUID m, RegisterBankAccountCommand c) {
    throw new UnsupportedOperationException("not needed at this altitude");
  }

  @Override
  public Optional<BankAccount> find(UUID m, UUID id) {
    return Optional.empty();
  }

  @Override
  public List<BankAccount> list(UUID m) {
    return List.of();
  }

  @Override
  public BankAccount verify(UUID m, UUID id, String code) {
    throw new UnsupportedOperationException("not needed at this altitude");
  }

  @Override
  public void revoke(UUID m, UUID id) {
    wireKeys.remove(id);
  }
}
