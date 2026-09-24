package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.merchants.domain.BankAccount;

/** A freshly registered account: the ONLY time the verification code is visible. */
public record IssuedBankAccount(BankAccount account, String verificationCode) {
}
