package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registry lifecycle. Codes are 256-bit, base64url, prefixed, shown once —
 *  the API-key precedent; hashes share the same SHA-256 helper. */
@Service
public class BankAccountsServiceImpl implements BankAccountsService {

  private static final String CODE_PREFIX = "nummus_bac_";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final BankAccountStore store;

  public BankAccountsServiceImpl(BankAccountStore store) {
    this.store = store;
  }

  @Override
  @Transactional
  public IssuedBankAccount register(UUID merchantPublicId, RegisterBankAccountCommand cmd) {
    Objects.requireNonNull(merchantPublicId, "merchantPublicId must not be null");
    Objects.requireNonNull(cmd, "command must not be null");
    TaxIds.requireValidTaxId(cmd.holderTaxId());
    byte[] secret = new byte[32];
    RANDOM.nextBytes(secret);
    String rawCode = CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    var account = new BankAccount(UUID.randomUUID(), merchantPublicId, cmd.bankCode(),
        cmd.branch(), cmd.accountNumber(), cmd.holderTaxId(), "PENDING_VERIFICATION",
        Instant.now(), null);
    try {
      store.insert(account, MerchantsServiceImpl.sha256Hex(rawCode));
    } catch (DuplicateKeyException e) {
      // The deterministic checks above make this the mid-flight race backstop —
      // the natural-key unique is the authority (the M2 commit-time 409 pattern).
      throw new DuplicateBankAccountException(cmd.bankCode(), cmd.branch(), cmd.accountNumber());
    }
    // Read back what persistence decided (defaults, truncations never — but
    // the returned row is what every later read will match on).
    var stored = store.findByPublicIdAndMerchant(merchantPublicId, account.publicId())
        .orElseThrow(() -> new IllegalStateException("bank account row missing after insert"));
    return new IssuedBankAccount(stored, rawCode);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BankAccount> find(UUID merchantPublicId, UUID publicId) {
    return store.findByPublicIdAndMerchant(merchantPublicId, publicId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<BankAccount> list(UUID merchantPublicId) {
    return store.listByMerchant(merchantPublicId, 50);
  }

  @Override
  @Transactional
  public BankAccount verify(UUID merchantPublicId, UUID publicId, String rawCode) {
    Objects.requireNonNull(rawCode, "code must not be null");
    if (!store.verify(merchantPublicId, publicId, MerchantsServiceImpl.sha256Hex(rawCode))) {
      var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
          .orElseThrow(() -> new UnknownBankAccountException(publicId));
      if (!"PENDING_VERIFICATION".equals(account.status())) {
        throw new BankAccountNotVerifiableException(publicId, account.status());
      }
      throw new InvalidVerificationCodeException(publicId);
    }
    return store.findByPublicIdAndMerchant(merchantPublicId, publicId)
        .orElseThrow(() -> new UnknownBankAccountException(publicId));
  }

  @Override
  @Transactional
  public void revoke(UUID merchantPublicId, UUID publicId) {
    if (!store.revoke(merchantPublicId, publicId)) {
      var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
          .orElseThrow(() -> new UnknownBankAccountException(publicId));
      throw new BankAccountNotVerifiableException(publicId, account.status());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public PayoutDestination requireVerifiedDestination(UUID merchantPublicId, UUID publicId) {
    var account = store.findByPublicIdAndMerchant(merchantPublicId, publicId)
        .orElseThrow(() -> new UnknownBankAccountException(publicId));
    if (!"VERIFIED".equals(account.status())) {
      throw new BankAccountNotVerifiedException(publicId, account.status());
    }
    return new PayoutDestination(account.publicId(),
        account.bankCode() + "-" + account.branch() + "-" + account.accountNumber());
  }
}
