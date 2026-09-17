package com.leandrossb.nummus.accounts.domain;

/** Command to open a new payment account. */
public record OpenAccountCommand(String holderName) {
}
