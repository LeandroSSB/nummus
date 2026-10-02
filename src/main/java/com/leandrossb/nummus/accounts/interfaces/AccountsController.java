package com.leandrossb.nummus.accounts.interfaces;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.interfaces.dto.AccountResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.BalanceResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.OpenAccountRequest;
import com.leandrossb.nummus.accounts.interfaces.dto.StatementResponse;
import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.csv.Csv;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.ledger.domain.Page;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountsController {

  private final AccountsService accounts;

  @Value("${nummus.export.max-rows:10000}")
  private int exportMaxRows;

  AccountsController(AccountsService accounts) {
    this.accounts = accounts;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<AccountResponse> create(AuthenticatedMerchant merchant,
      @Valid @RequestBody OpenAccountRequest request) {
    var account = accounts.open(merchant.merchantPublicId(), new OpenAccountCommand(request.holderName()));
    return ResponseEntity
        .created(URI.create("/v1/accounts/" + account.publicId()))
        .body(AccountResponse.from(account));
  }

  @GetMapping("/{id}")
  AccountResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return AccountResponse.from(accounts.get(merchant.merchantPublicId(), id));
  }

  @GetMapping("/{id}/balance")
  BalanceResponse balance(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return BalanceResponse.from(accounts.composition(merchant.merchantPublicId(), id));
  }

  @GetMapping("/{id}/statement")
  ResponseEntity<?> statement(AuthenticatedMerchant merchant,
      @RequestHeader(value = "Accept", required = false) String accept,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    if (Csv.wantsCsv(accept)) {
      // The probe rides the export cap clamped to Page's own ceiling (500) —
      // the statement's pagination primitive rejects anything larger, so the
      // export's effective row bound here is the tighter of the two.
      var probeLimit = Math.min(exportMaxRows + 1, 500);
      var statement = accounts.statement(merchant.merchantPublicId(), id, new Page(0, probeLimit));
      // Two arms: the property arm fires when the configured cap was exceeded;
      // the probe-full arm fires when Page's 500 ceiling filled the probe —
      // there may be more lines. A complete exactly-full export gets the
      // marker too: errs safe over silently incomplete accounting output.
      var truncated = statement.lines().size() > exportMaxRows
          || (probeLimit <= exportMaxRows && statement.lines().size() == probeLimit);
      // The probe-full arm can fire while the fetched page holds fewer lines
      // than the configured cap (Page's ceiling is the tighter bound), so the
      // slice clamps to what was actually fetched.
      var lines = truncated
          ? statement.lines().subList(0, Math.min(exportMaxRows, statement.lines().size()))
          : statement.lines();
      var sb = new StringBuilder(Csv.render(
          List.of("balance", "pendingIncoming", "reservedOutgoing"),
          List.of(List.of(statement.balance().amount().toPlainString(),
              statement.pendingIncoming().amount().toPlainString(),
              statement.reservedOutgoing().amount().toPlainString()))));
      sb.append("\r\n");
      var rows = new java.util.ArrayList<List<String>>(lines.size());
      for (var line : lines) {
        rows.add(List.of(String.valueOf(line.bookedAt()), String.valueOf(line.transactionPublicId()),
            line.memo(), line.direction().name(), line.amount().amount().toPlainString(),
            line.amount().currency().getCurrencyCode()));
      }
      sb.append(Csv.render(List.of("bookedAt", "transactionPublicId", "memo", "direction",
          "amount", "currency"), rows));
      if (truncated) {
        sb.append("# truncated: true\r\n");
      }
      return ResponseEntity.ok()
          .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
          .header("Content-Disposition", "attachment; filename=\"statement-"
              + merchant.merchantPublicId().toString().substring(0, 8) + ".csv\"")
          .body(sb.toString());
    }
    return ResponseEntity.ok().body(StatementResponse.from(
        accounts.statement(merchant.merchantPublicId(), id, new Page(offset, limit))));
  }

  @Idempotent
  @PostMapping("/{id}/freeze")
  ResponseEntity<AccountResponse> freeze(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return ResponseEntity.ok(AccountResponse.from(accounts.freeze(merchant.merchantPublicId(), id)));
  }

  @Idempotent
  @PostMapping("/{id}/unfreeze")
  ResponseEntity<AccountResponse> unfreeze(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return ResponseEntity.ok(AccountResponse.from(accounts.unfreeze(merchant.merchantPublicId(), id)));
  }

  @Idempotent
  @PostMapping("/{id}/close")
  ResponseEntity<AccountResponse> close(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return ResponseEntity.ok(AccountResponse.from(accounts.close(merchant.merchantPublicId(), id)));
  }
}
