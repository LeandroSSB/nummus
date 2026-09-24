package com.leandrossb.nummus.testutils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.BankAccountsService;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.merchants.domain.BankAccount;
import com.leandrossb.nummus.merchants.domain.RegisterBankAccountCommand;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The REST test drivers the API suites share: minting operator auth,
 *  creating a merchant over HTTP, and the loopback webhook URL. Each helper
 *  is the exact behavior of the private copies it replaces — a fresh
 *  operator key per call, the first API key's secret read from the creation
 *  response, unique loopback paths per fixture. */
public final class ApiDrivers {

  private static final AtomicLong BANK_ACCOUNT_SEQ = new AtomicLong();

  private ApiDrivers() {}

  /** A fresh operator bearer value: mints a new operator key per call, so
   *  each caller gets its own key (and its own rate-limit bucket). Bearer
   *  probes exist to authenticate, not to act — their mint is unattributed. */
  public static String operatorAuth(OperatorKeysService operatorKeys) {
    return "Bearer " + operatorKeys.create("probe", null, null).secret();
  }

  /** POSTs the merchant as the operator and returns the first API key's
   *  secret — shown exactly once, on the creation response. */
  public static String createMerchantAndGetKey(MockMvc mockMvc, String operatorAuth, String name)
      throws Exception {
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
        .andExpect(status().isCreated()).andReturn();
    return JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey.secret");
  }

  /** Port 9 (discard): a loopback URL the URL policy accepts that nothing
   *  ever contacts. Unique path per registration keeps fixtures disjoint. */
  public static String loopbackUrl(String tag) {
    return "http://127.0.0.1:9/" + tag + "-" + UUID.randomUUID();
  }

  /** Registers and verifies a payout destination for the merchant, returning
   *  the stored account — every call a unique account number, so fixtures
   *  never collide on the natural-key unique. The wire key is
   *  {@code 123-4567-<accountNumber>}. */
  public static BankAccount registerVerifiedBankAccount(BankAccountsService bankAccounts,
      UUID merchantPublicId) {
    String accountNumber = String.format("100%06d", BANK_ACCOUNT_SEQ.incrementAndGet());
    var issued = bankAccounts.register(merchantPublicId,
        new RegisterBankAccountCommand("123", "4567", accountNumber, "11144477735"));
    bankAccounts.verify(merchantPublicId, issued.account().publicId(), issued.verificationCode());
    return issued.account();
  }
}
