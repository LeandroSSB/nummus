package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.AccountStatus;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.application.Ledger;
import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.AccountType;
import com.leandrossb.nummus.ledger.domain.Direction;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.Page;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountsServiceImpl implements AccountsService {

  private static final Currency BRL = Currency.getInstance("BRL");
  private static final int HOLDER_NAME_MAX = 200;

  private final Ledger ledger;
  private final AccountsRepository repository;

  public AccountsServiceImpl(Ledger ledger, AccountsRepository repository) {
    this.ledger = ledger;
    this.repository = repository;
  }

  @Override
  @Transactional
  public PaymentAccount open(OpenAccountCommand cmd) {
    Objects.requireNonNull(cmd, "command must not be null");
    String holderName = cmd.holderName() == null ? "" : cmd.holderName().trim();
    if (holderName.isEmpty()) {
      throw new IllegalArgumentException("holder name must not be blank");
    }
    if (holderName.length() > HOLDER_NAME_MAX) {
      throw new IllegalArgumentException("holder name must be at most " + HOLDER_NAME_MAX + " characters");
    }
    UUID publicId = UUID.randomUUID();
    var backing = ledger.openAccount(new com.leandrossb.nummus.ledger.application.OpenAccountCommand(
        "payable " + publicId.toString().substring(0, 8), AccountType.LIABILITY, BRL));
    return repository.insert(new PaymentAccount(publicId, holderName, AccountStatus.ACTIVE,
        Instant.now(), null, backing.publicId()));
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentAccount get(UUID publicId) {
    return require(publicId);
  }

  @Override
  @Transactional
  public PaymentAccount freeze(UUID publicId) {
    return transition(publicId, AccountStatus.FROZEN, null, ledger::freezeAccount);
  }

  @Override
  @Transactional
  public PaymentAccount unfreeze(UUID publicId) {
    var current = require(publicId);
    if (current.status() == AccountStatus.ACTIVE) {
      throw new IllegalArgumentException("payment account is not FROZEN: " + publicId);
    }
    return transition(publicId, AccountStatus.ACTIVE, null, ledger::unfreezeAccount);
  }

  @Override
  @Transactional
  public PaymentAccount close(UUID publicId) {
    return transition(publicId, AccountStatus.CLOSED, Instant.now(), ledger::closeAccount);
  }

  @Override
  @Transactional(readOnly = true)
  public Money balance(UUID publicId) {
    var account = require(publicId);
    return naturalSigned(account, ledger.balance(account.ledgerAccountPublicId()));
  }

  @Override
  @Transactional(readOnly = true)
  public AccountStatement statement(UUID publicId, Page page) {
    Objects.requireNonNull(page, "page must not be null");
    var account = require(publicId);
    var raw = ledger.statement(account.ledgerAccountPublicId(), page);
    return new AccountStatement(raw.account(), naturalSigned(account, raw.balance()), raw.lines());
  }

  private PaymentAccount require(UUID publicId) {
    return repository.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownPaymentAccountException(publicId));
  }

  private PaymentAccount transition(UUID publicId, AccountStatus target, Instant closedAt,
      Consumer<UUID> ledgerTransition) {
    var current = require(publicId);
    if (current.status() == AccountStatus.CLOSED) {
      throw new PaymentAccountNotActiveException(publicId, current.status());
    }
    ledgerTransition.accept(current.ledgerAccountPublicId());
    repository.updateStatus(publicId, target, closedAt);
    return get(publicId);
  }

  private Money naturalSigned(PaymentAccount account, Money raw) {
    var backing = ledger.getAccount(account.ledgerAccountPublicId());
    return backing.type().normalBalance() == Direction.CREDIT
        ? Money.of(raw.amount().negate(), raw.currency())
        : raw;
  }
}
