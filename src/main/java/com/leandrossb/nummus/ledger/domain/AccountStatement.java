package com.leandrossb.nummus.ledger.domain;

import java.util.List;

/** Paginated account view: the account, its derived balance, and postings newest first. */
public record AccountStatement(LedgerAccount account, Money balance, List<StatementLine> lines) {
}
