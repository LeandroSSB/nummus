package com.leandrossb.nummus.accounts.interfaces.dto;

import com.leandrossb.nummus.ledger.domain.AccountStatement;
import com.leandrossb.nummus.ledger.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST view of a statement: natural-signed balance plus postings newest first. */
public record StatementResponse(BigDecimal balance, String currency, List<Line> lines) {

  public static StatementResponse from(AccountStatement statement) {
    return new StatementResponse(statement.balance().amount(),
        statement.balance().currency().getCurrencyCode(),
        statement.lines().stream().map(Line::from).toList());
  }

  /** One posting with its originating transaction context, exactly as booked. */
  record Line(Instant bookedAt, UUID transactionPublicId, String memo, String direction,
      BigDecimal amount) {

    static Line from(com.leandrossb.nummus.ledger.domain.StatementLine line) {
      return new Line(line.bookedAt(), line.transactionPublicId(), line.memo(),
          line.direction().name(), line.amount().amount());
    }
  }
}
