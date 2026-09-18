package com.leandrossb.nummus.accounts.interfaces;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.accounts.interfaces.dto.AccountResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.BalanceResponse;
import com.leandrossb.nummus.accounts.interfaces.dto.OpenAccountRequest;
import com.leandrossb.nummus.accounts.interfaces.dto.StatementResponse;
import com.leandrossb.nummus.ledger.domain.Page;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
class AccountsController {

  private final AccountsService accounts;

  AccountsController(AccountsService accounts) {
    this.accounts = accounts;
  }

  @PostMapping
  ResponseEntity<AccountResponse> create(@Valid @RequestBody OpenAccountRequest request) {
    var account = accounts.open(new OpenAccountCommand(request.holderName()));
    return ResponseEntity
        .created(URI.create("/v1/accounts/" + account.publicId()))
        .body(AccountResponse.from(account));
  }

  @GetMapping("/{id}")
  AccountResponse get(@PathVariable UUID id) {
    return AccountResponse.from(accounts.get(id));
  }

  @GetMapping("/{id}/balance")
  BalanceResponse balance(@PathVariable UUID id) {
    return BalanceResponse.from(accounts.balance(id));
  }

  @GetMapping("/{id}/statement")
  StatementResponse statement(@PathVariable UUID id,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit) {
    return StatementResponse.from(accounts.statement(id, new Page(offset, limit)));
  }

  @PostMapping("/{id}/freeze")
  AccountResponse freeze(@PathVariable UUID id) {
    return AccountResponse.from(accounts.freeze(id));
  }

  @PostMapping("/{id}/unfreeze")
  AccountResponse unfreeze(@PathVariable UUID id) {
    return AccountResponse.from(accounts.unfreeze(id));
  }

  @PostMapping("/{id}/close")
  AccountResponse close(@PathVariable UUID id) {
    return AccountResponse.from(accounts.close(id));
  }
}
