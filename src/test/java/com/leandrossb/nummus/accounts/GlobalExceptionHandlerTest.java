package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.accounts.interfaces.GlobalExceptionHandler;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
  private final UUID id = UUID.randomUUID();

  @Test
  void unknownIdsMapTo404() {
    assertEquals(HttpStatus.NOT_FOUND.value(), handler.notFound(new UnknownPaymentAccountException(id)).getStatus());
    assertEquals(HttpStatus.NOT_FOUND.value(), handler.notFound(new UnknownAccountException(id)).getStatus());
    assertEquals(HttpStatus.NOT_FOUND.value(), handler.notFound(new UnknownTransactionException(id)).getStatus());
  }

  @Test
  void lifecycleConflictsMapTo409() {
    PaymentAccountNotActiveException payment =
        new PaymentAccountNotActiveException(id, com.leandrossb.nummus.accounts.domain.AccountStatus.CLOSED);
    assertEquals(HttpStatus.CONFLICT.value(), handler.conflict(payment).getStatus());
    assertEquals(HttpStatus.CONFLICT.value(),
        handler.conflict(new AccountNotActiveException(id,
            com.leandrossb.nummus.ledger.domain.AccountStatus.CLOSED)).getStatus());
    assertEquals(HttpStatus.CONFLICT.value(),
        handler.conflict(new TransactionAlreadyReversedException(id, null)).getStatus());
  }

  @Test
  void commitTimeTriggerFailuresMapTo409() {
    var unbalanced = new TransactionSystemException("commit",
        new RuntimeException("ERROR: transaction 42 is unbalanced: debits minus credits = 10.0000"));
    assertEquals(HttpStatus.CONFLICT.value(), handler.commitConflict(unbalanced).getStatus());

    var frozen = new TransactionSystemException("commit",
        new RuntimeException("ERROR: transaction 42 posts to a non-ACTIVE account"));
    assertEquals(HttpStatus.CONFLICT.value(), handler.commitConflict(frozen).getStatus());

    var frozenOutsideTx = new UncategorizedSQLException("statement", "insert",
        new SQLException("ERROR: transaction 42 posts to a non-ACTIVE account"));
    assertEquals(HttpStatus.CONFLICT.value(), handler.commitConflict(frozenOutsideTx).getStatus());
  }

  @Test
  void unrelatedCommitFailuresRethrow() {
    var unrelated = new TransactionSystemException("commit",
        new RuntimeException("connection reset by peer"));
    assertThrows(RuntimeException.class, () -> handler.commitConflict(unrelated));
  }

  @Test
  void badRequestsMapTo400() {
    assertEquals(HttpStatus.BAD_REQUEST.value(),
        handler.badRequest(new IllegalArgumentException("holder name must not be blank")).getStatus());
  }
}
