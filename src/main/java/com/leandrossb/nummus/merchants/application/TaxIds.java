package com.leandrossb.nummus.merchants.application;

import java.util.Objects;

/** CPF/CNPJ check-digit arithmetic — shape says well-formed, never real. */
public final class TaxIds {

  private TaxIds() {
  }

  /** @throws IllegalArgumentException wrong length or failing check digits. */
  public static void requireValidTaxId(String taxId) {
    Objects.requireNonNull(taxId, "holderTaxId must not be null");
    if (taxId.length() == 11) {
      requireDocument(taxId, new int[] {10, 9, 8, 7, 6, 5, 4, 3, 2},
          new int[] {11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
    } else if (taxId.length() == 14) {
      requireDocument(taxId, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2},
          new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
    } else {
      throw new IllegalArgumentException(
          "holderTaxId must be 11 (CPF) or 14 (CNPJ) digits: " + taxId.length());
    }
  }

  private static void requireDocument(String taxId, int[] firstWeights, int[] secondWeights) {
    if (taxId.chars().distinct().count() == 1) {
      throw new IllegalArgumentException(
          "holderTaxId must not be a repeated-digit document: " + taxId);
    }
    int length = taxId.length();
    if (digitAt(taxId, length - 2) != checkDigit(taxId, firstWeights)
        || digitAt(taxId, length - 1) != checkDigit(taxId, secondWeights)) {
      throw new IllegalArgumentException("holderTaxId check digits do not match: " + taxId);
    }
  }

  private static int checkDigit(String taxId, int[] weights) {
    int sum = 0;
    for (int i = 0; i < weights.length; i++) {
      sum += digitAt(taxId, i) * weights[i];
    }
    int rest = sum % 11;
    return rest < 2 ? 0 : 11 - rest;
  }

  private static int digitAt(String taxId, int index) {
    return taxId.charAt(index) - '0';
  }
}
