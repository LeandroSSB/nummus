package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.payments.application.NetworkTransfer;
import com.leandrossb.nummus.psp_simulator.domain.ChargeNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedTransfer;
import com.leandrossb.nummus.psp_simulator.domain.TransferNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.UnknownChargeException;
import com.leandrossb.nummus.psp_simulator.domain.UnknownTransferException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulatorServiceImpl implements SimulatorService {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final ChargeStore chargeStore;
  private final TransferStore transferStore;

  public SimulatorServiceImpl(ChargeStore chargeStore, TransferStore transferStore) {
    this.chargeStore = chargeStore;
    this.transferStore = transferStore;
  }

  @Override
  @Transactional
  public NetworkCharge create(Money amount) {
    Objects.requireNonNull(amount, "amount must not be null");
    var charge = chargeStore.insert(new SimulatedCharge(UUID.randomUUID(), amount,
        ChargeStatus.PENDING, Instant.now(), Instant.now()));
    return toNetworkCharge(charge);
  }

  @Override
  @Transactional(readOnly = true)
  public NetworkCharge get(UUID publicId) {
    return toNetworkCharge(require(publicId));
  }

  @Override
  @Transactional
  public NetworkCharge pay(UUID publicId) {
    return transition(publicId, ChargeStatus.SUCCEEDED);
  }

  @Override
  @Transactional
  public NetworkCharge fail(UUID publicId) {
    return transition(publicId, ChargeStatus.FAILED);
  }

  @Override
  @Transactional(readOnly = true)
  public List<NetworkSettlement> settlementReport(Instant from, Instant to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    return chargeStore.findSucceededBetween(from, to).stream()
        .map(charge -> new NetworkSettlement(charge.publicId(), charge.amount(), charge.updatedAt()))
        .toList();
  }

  private NetworkCharge transition(UUID publicId, ChargeStatus target) {
    require(publicId);
    if (!chargeStore.transition(publicId, target)) {
      throw new ChargeNotPendingException(publicId, require(publicId).status());
    }
    return toNetworkCharge(require(publicId));
  }

  private SimulatedCharge require(UUID publicId) {
    return chargeStore.findByPublicId(publicId).orElseThrow(() -> new UnknownChargeException(publicId));
  }

  private static NetworkCharge toNetworkCharge(SimulatedCharge charge) {
    return new NetworkCharge(charge.publicId(), charge.amount(), charge.status());
  }

  @Override
  @Transactional
  public NetworkTransfer createTransfer(Money amount, String destinationBankKey) {
    Objects.requireNonNull(amount, "amount must not be null");
    Objects.requireNonNull(destinationBankKey, "destinationBankKey must not be null");
    var transfer = transferStore.insert(new SimulatedTransfer(UUID.randomUUID(), amount,
        destinationBankKey, ChargeStatus.PENDING, Instant.now(), Instant.now()));
    return toNetworkTransfer(transfer);
  }

  @Override
  @Transactional(readOnly = true)
  public NetworkTransfer getTransfer(UUID publicId) {
    return toNetworkTransfer(requireTransfer(publicId));
  }

  @Override
  @Transactional
  public NetworkTransfer payTransfer(UUID publicId) {
    return transitionTransfer(publicId, ChargeStatus.SUCCEEDED);
  }

  @Override
  @Transactional
  public NetworkTransfer failTransfer(UUID publicId) {
    return transitionTransfer(publicId, ChargeStatus.FAILED);
  }

  private NetworkTransfer transitionTransfer(UUID publicId, ChargeStatus target) {
    requireTransfer(publicId);
    if (!transferStore.transition(publicId, target)) {
      throw new TransferNotPendingException(publicId, requireTransfer(publicId).status());
    }
    return toNetworkTransfer(requireTransfer(publicId));
  }

  private SimulatedTransfer requireTransfer(UUID publicId) {
    return transferStore.findByPublicId(publicId)
        .orElseThrow(() -> new UnknownTransferException(publicId));
  }

  private static NetworkTransfer toNetworkTransfer(SimulatedTransfer transfer) {
    return new NetworkTransfer(transfer.publicId(), transfer.amount(),
        transfer.destinationBankKey(), transfer.status());
  }
}
