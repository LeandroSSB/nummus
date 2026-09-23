package com.leandrossb.nummus.merchants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.merchants.application.TaxIds;
import org.junit.jupiter.api.Test;

class TaxIdsTest {

  @Test
  void acceptsKnownGoodCpfAndCnpj() {
    assertDoesNotThrow(() -> TaxIds.requireValidTaxId("11144477735"));
    assertDoesNotThrow(() -> TaxIds.requireValidTaxId("11222333000181"));
  }

  @Test
  void rejectsWrongCheckDigitsOnBothKinds() {
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11144477736"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11222333000182"));
  }

  @Test
  void rejectsWrongLengths() {
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("1114447773"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("111444777355555"));
    assertThrows(NullPointerException.class, () -> TaxIds.requireValidTaxId(null));
  }

  @Test
  void rejectsRepeatedDigitPatterns() {
    // All-same-digit tax ids pass the arithmetic but are never real documents.
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("11111111111"));
    assertThrows(IllegalArgumentException.class, () -> TaxIds.requireValidTaxId("00000000000000"));
  }
}
