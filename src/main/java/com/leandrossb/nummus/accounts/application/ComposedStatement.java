package com.leandrossb.nummus.accounts.application;

import com.leandrossb.nummus.accounts.domain.PaymentAccount;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.util.List;

/**
 * A statement read composed with the in-flight sums, so one response answers
 * what is booked, what is coming, and what is locked — the same three figures
 * the balance read carries, over the same commit frontier.
 */
public record ComposedStatement(PaymentAccount account, Money balance, Money pendingIncoming,
    Money reservedOutgoing, List<StatementLine> lines) {}
