package com.leandrossb.nummus.interfaces;

import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.InvalidMoneyException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Application-wide RFC 7807 error surface for the REST API. Commit-time failures raised
 * by the database's enforcement triggers surface as 409 conflicts — the
 * deterministic variants of the same violations already fail fast in the
 * services; this catches the mid-flight race (an account frozen between
 * validation and COMMIT) and any writer that bypasses the services.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler({UnknownPaymentAccountException.class, UnknownAccountException.class,
      UnknownTransactionException.class})
  public ProblemDetail notFound(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({PaymentAccountNotActiveException.class, AccountNotActiveException.class,
      TransactionAlreadyReversedException.class})
  public ProblemDetail conflict(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, InvalidMoneyException.class,
      CurrencyMismatchException.class, MethodArgumentNotValidException.class,
      MethodArgumentTypeMismatchException.class})
  public ProblemDetail badRequest(Exception e) {
    String detail = e instanceof MethodArgumentNotValidException validation
        ? validation.getBindingResult().getAllErrors().get(0).getDefaultMessage()
        : e.getMessage();
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler({TransactionSystemException.class, DataAccessException.class})
  public ProblemDetail commitConflict(RuntimeException e) {
    String root = rootMessage(e);
    if (root != null && (root.contains("is unbalanced") || root.contains("non-ACTIVE"))) {
      return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
          "transaction rejected by ledger invariants: " + root);
    }
    throw e;
  }

  private static String rootMessage(Throwable t) {
    String message = t.getMessage();
    Throwable cause = t.getCause();
    int depth = 0;
    while (cause != null && depth++ < 100) {
      message = cause.getMessage();
      cause = cause.getCause();
    }
    return message;
  }
}
