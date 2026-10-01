package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.accounts.application.ComposedStatement;
import com.leandrossb.nummus.ledger.domain.StatementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST view of a statement: natural-signed balance, the in-flight sums, and
 *  postings newest first. */
public record StatementResponse(BigDecimal balance, BigDecimal pendingIncoming,
    BigDecimal reservedOutgoing, String currency, List<Line> lines) {

  public static StatementResponse from(ComposedStatement statement) {
    return new StatementResponse(statement.balance().amount(),
        statement.pendingIncoming().amount(), statement.reservedOutgoing().amount(),
        statement.balance().currency().getCurrencyCode(),
        statement.lines().stream().map(Line::from).toList());
  }

  /** One posting with its originating transaction context, exactly as booked. */
  record Line(Instant bookedAt, UUID transactionPublicId, String memo, String direction,
      BigDecimal amount, String currency) {

    static Line from(StatementLine line) {
      return new Line(line.bookedAt(), line.transactionPublicId(), line.memo(),
          line.direction().name(), line.amount().amount(),
          line.amount().currency().getCurrencyCode());
    }
  }
}
