package com.leandrossb.nummus.merchants.application;

/** Raised when an active registration already exists for the natural key. */
public final class DuplicateBankAccountException extends RuntimeException {

  public DuplicateBankAccountException(String bankCode, String branch, String accountNumber) {
    super("bank account already registered: " + bankCode + "-" + branch + "-" + accountNumber);
  }
}
