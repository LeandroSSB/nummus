package com.leandrossb.nummus.psp_simulator.application;

import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.payments.application.ChargeStatus;
import com.leandrossb.nummus.payments.application.NetworkCharge;
import com.leandrossb.nummus.psp_simulator.domain.ChargeNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.SimulatedCharge;
import com.leandrossb.nummus.psp_simulator.domain.UnknownChargeException;
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

  public SimulatorServiceImpl(ChargeStore chargeStore) {
    this.chargeStore = chargeStore;
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
}
