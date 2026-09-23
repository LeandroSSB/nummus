package com.leandrossb.nummus.merchants.interfaces;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.UnknownBankAccountException;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import com.leandrossb.nummus.merchants.interfaces.dto.BankAccountResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.IssuedBankAccountResponse;
import com.leandrossb.nummus.merchants.interfaces.dto.RegisterBankAccountRequest;
import com.leandrossb.nummus.merchants.interfaces.dto.VerifyBankAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-serve registry surface: the authenticated merchant manages its own
 *  payout destinations. */
@RestController
@RequestMapping("/v1/bank-accounts")
class BankAccountsController {

  private final BankAccountsService bankAccounts;

  BankAccountsController(BankAccountsService bankAccounts) {
    this.bankAccounts = bankAccounts;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<IssuedBankAccountResponse> register(AuthenticatedMerchant merchant,
      @Valid @RequestBody RegisterBankAccountRequest request) {
    var issued = bankAccounts.register(merchant.merchantPublicId(),
        new RegisterBankAccountCommand(request.bankCode(), request.branch(),
            request.accountNumber(), request.holderTaxId()));
    return ResponseEntity
        .created(URI.create("/v1/bank-accounts/" + issued.account().publicId()))
        .body(IssuedBankAccountResponse.from(issued));
  }

  @GetMapping
  List<BankAccountResponse> list(AuthenticatedMerchant merchant) {
    return bankAccounts.list(merchant.merchantPublicId()).stream()
        .map(BankAccountResponse::from).toList();
  }

  @GetMapping("/{id}")
  BankAccountResponse get(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    return bankAccounts.find(merchant.merchantPublicId(), id)
        .map(BankAccountResponse::from)
        .orElseThrow(() -> new UnknownBankAccountException(id));
  }

  @Idempotent
  @PostMapping("/{id}/verify")
  BankAccountResponse verify(AuthenticatedMerchant merchant, @PathVariable UUID id,
      @Valid @RequestBody VerifyBankAccountRequest request) {
    return BankAccountResponse.from(
        bankAccounts.verify(merchant.merchantPublicId(), id, request.code()));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> revoke(AuthenticatedMerchant merchant, @PathVariable UUID id) {
    bankAccounts.revoke(merchant.merchantPublicId(), id);
    return ResponseEntity.noContent().build();
  }
}
