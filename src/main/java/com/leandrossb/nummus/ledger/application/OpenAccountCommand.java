package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.AccountType;
import java.util.Currency;

/** Command to open a new ledger account. */
public record OpenAccountCommand(String name, AccountType type, Currency currency) {
}
