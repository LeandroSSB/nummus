package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.merchants.application.FeeSchedule;
import com.leandrossb.nummus.merchants.application.InvalidFeeScheduleException;
import com.leandrossb.nummus.merchants.application.MerchantsService;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantsFeeScheduleTest extends IntegrationTestBase {

  @Autowired
  private MerchantsService merchants;

  @Test
  void createPersistsTheScheduleAndDefaultsToZero() {
    var merchant = merchants.create("fee merchant",
        new FeeSchedule(new BigDecimal("0.0099"), new BigDecimal("0.39")));
    var stored = merchants.findFeeSchedule(merchant.publicId()).orElseThrow();
    assertEquals(0, stored.rate().compareTo(new BigDecimal("0.0099")));
    assertEquals(0, stored.fixedAmount().compareTo(new BigDecimal("0.39")));

    var plain = merchants.create("plain merchant", FeeSchedule.ZERO);
    var zero = merchants.findFeeSchedule(plain.publicId()).orElseThrow();
    assertEquals(0, zero.rate().compareTo(BigDecimal.ZERO));
    assertEquals(0, zero.fixedAmount().compareTo(BigDecimal.ZERO));
  }

  @Test
  void updateReplacesTheScheduleAndReportsUnknownMerchants() {
    var merchant = merchants.create("updatee", FeeSchedule.ZERO);
    assertTrue(merchants.updateFeeSchedule(merchant.publicId(),
        new FeeSchedule(new BigDecimal("0.015"), BigDecimal.ZERO)));
    assertEquals(0, merchants.findFeeSchedule(merchant.publicId()).orElseThrow()
        .rate().compareTo(new BigDecimal("0.015")));
    assertFalse(merchants.updateFeeSchedule(UUID.randomUUID(), FeeSchedule.ZERO));
  }

  @Test
  void invalidSchedulesAreRejectedAtConstruction() {
    assertThrows(InvalidFeeScheduleException.class,
        () -> new FeeSchedule(new BigDecimal("-0.1"), BigDecimal.ZERO));
    assertThrows(InvalidFeeScheduleException.class,
        () -> new FeeSchedule(BigDecimal.ONE, BigDecimal.ZERO));
    assertThrows(InvalidFeeScheduleException.class,
        () -> new FeeSchedule(BigDecimal.ZERO, new BigDecimal("-0.01")));
    assertThrows(NullPointerException.class,
        () -> new FeeSchedule(null, BigDecimal.ZERO));
  }
}
