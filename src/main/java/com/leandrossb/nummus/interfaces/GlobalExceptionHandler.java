package com.leandrossb.nummus.interfaces;

import com.leandrossb.nummus.accounts.domain.PaymentAccountNotActiveException;
import com.leandrossb.nummus.accounts.domain.UnknownPaymentAccountException;
import com.leandrossb.nummus.conciliation.application.DuplicateSettlementLinesException;
import com.leandrossb.nummus.conciliation.application.UnknownConciliationReportException;
import com.leandrossb.nummus.interfaces.auth.MerchantKeyRequiredException;
import com.leandrossb.nummus.interfaces.auth.MerchantUnauthorizedException;
import com.leandrossb.nummus.interfaces.auth.OperatorKeyRequiredException;
import com.leandrossb.nummus.interfaces.auth.OperatorUnauthorizedException;
import com.leandrossb.nummus.interfaces.idempotency.IdempotencyKeyReuseException;
import com.leandrossb.nummus.interfaces.idempotency.MissingIdempotencyKeyException;
import com.leandrossb.nummus.merchants.application.BootstrapAlreadyUsedException;
import com.leandrossb.nummus.merchants.application.BootstrapUnavailableException;
import com.leandrossb.nummus.merchants.application.InvalidBootstrapTokenException;
import com.leandrossb.nummus.merchants.application.InvalidFeeScheduleException;
import com.leandrossb.nummus.merchants.application.InvalidKeyExpiryException;
import com.leandrossb.nummus.merchants.application.InvalidOperatorLabelException;
import com.leandrossb.nummus.merchants.application.UnknownApiKeyException;
import com.leandrossb.nummus.merchants.application.UnknownMerchantException;
import com.leandrossb.nummus.ledger.domain.AccountNotActiveException;
import com.leandrossb.nummus.ledger.domain.CurrencyMismatchException;
import com.leandrossb.nummus.ledger.domain.InvalidMoneyException;
import com.leandrossb.nummus.ledger.domain.TransactionAlreadyReversedException;
import com.leandrossb.nummus.ledger.domain.UnknownAccountException;
import com.leandrossb.nummus.ledger.domain.UnknownTransactionException;
import com.leandrossb.nummus.payments.domain.ChargeAmountMismatchException;
import com.leandrossb.nummus.payments.domain.ConcurrentPayoutException;
import com.leandrossb.nummus.payments.domain.ConcurrentSettlementException;
import com.leandrossb.nummus.payments.domain.InsufficientFundsException;
import com.leandrossb.nummus.payments.domain.TransferAmountMismatchException;
import com.leandrossb.nummus.payments.domain.UnknownPaymentIntentException;
import com.leandrossb.nummus.payments.domain.UnknownPayoutException;
import com.leandrossb.nummus.psp_simulator.domain.ChargeNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.TransferNotPendingException;
import com.leandrossb.nummus.psp_simulator.domain.UnknownChargeException;
import com.leandrossb.nummus.psp_simulator.domain.UnknownTransferException;
import com.leandrossb.nummus.webhooks.application.UnsafeWebhookUrlException;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookDeliveryException;
import com.leandrossb.nummus.webhooks.domain.UnknownWebhookEndpointException;
import java.time.format.DateTimeParseException;
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
      UnknownTransactionException.class, UnknownChargeException.class,
      UnknownPaymentIntentException.class, UnknownWebhookEndpointException.class,
      UnknownWebhookDeliveryException.class,
      UnknownConciliationReportException.class, UnknownMerchantException.class,
      UnknownApiKeyException.class, UnknownTransferException.class,
      UnknownPayoutException.class})
  public ProblemDetail notFound(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler({MerchantUnauthorizedException.class, OperatorUnauthorizedException.class})
  ProblemDetail unauthorized(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler({MerchantKeyRequiredException.class, OperatorKeyRequiredException.class})
  ProblemDetail keyRequired(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
  }

  @ExceptionHandler(BootstrapUnavailableException.class)
  ProblemDetail bootstrapUnavailable(BootstrapUnavailableException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(BootstrapAlreadyUsedException.class)
  ProblemDetail bootstrapAlreadyUsed(BootstrapAlreadyUsedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.GONE, e.getMessage());
  }

  @ExceptionHandler(InvalidBootstrapTokenException.class)
  ProblemDetail invalidBootstrapToken(InvalidBootstrapTokenException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler({PaymentAccountNotActiveException.class, AccountNotActiveException.class,
      TransactionAlreadyReversedException.class, ChargeNotPendingException.class,
      TransferNotPendingException.class, ConcurrentSettlementException.class,
      ConcurrentPayoutException.class})
  public ProblemDetail conflict(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyReuseException.class)
  ProblemDetail idempotencyReuse(IdempotencyKeyReuseException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }

  @ExceptionHandler(DuplicateSettlementLinesException.class)
  ProblemDetail duplicateSettlementLines(DuplicateSettlementLinesException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidFeeScheduleException.class)
  ProblemDetail invalidFeeSchedule(InvalidFeeScheduleException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidKeyExpiryException.class)
  ProblemDetail invalidKeyExpiry(InvalidKeyExpiryException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InvalidOperatorLabelException.class)
  ProblemDetail invalidOperatorLabel(InvalidOperatorLabelException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(UnsafeWebhookUrlException.class)
  ProblemDetail unsafeWebhookUrl(UnsafeWebhookUrlException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MissingIdempotencyKeyException.class)
  ProblemDetail idempotencyKeyMissing(MissingIdempotencyKeyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(InsufficientFundsException.class)
  ProblemDetail insufficientFunds(InsufficientFundsException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
  }

  @ExceptionHandler({ChargeAmountMismatchException.class, TransferAmountMismatchException.class})
  ProblemDetail invariantBreach(RuntimeException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, InvalidMoneyException.class,
      CurrencyMismatchException.class, MethodArgumentNotValidException.class,
      MethodArgumentTypeMismatchException.class, DateTimeParseException.class})
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
