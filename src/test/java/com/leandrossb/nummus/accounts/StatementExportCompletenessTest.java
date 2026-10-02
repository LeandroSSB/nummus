package com.leandrossb.nummus.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentClearingAccount;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The statement export above the JSON pagination ceiling: with the export
 *  bound past {@code Page}'s 500-row limit, the CSV carries every journal
 *  line — completeness is not paginated away — and the truncation marker
 *  fires only when rows were actually dropped. The JSON statement keeps its
 *  own paged shape untouched. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "nummus.export.max-rows=600")
class StatementExportCompletenessTest extends IntegrationTestBase {

  private static final int SEEDED_BULK_LINES = 501;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private OperatorKeysService operatorKeys;

  private UUID merchantId;
  private String merchantKey;
  private UUID bulkAccount;
  private UUID smallAccount;

  /** One merchant, two accounts: one seeded past the JSON ceiling by a single
   *  line — exactly at no bound, so any marker would be a lie — and one well
   *  under every bound as the sub-bound control. */
  @BeforeEach
  void createExportSizedFixture() throws Exception {
    String operatorAuth = "Bearer " + operatorKeys.create("probe", null, null).secret();
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", operatorAuth)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Statement Export Completeness Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String body = created.getResponse().getContentAsString();
    merchantKey = com.jayway.jsonpath.JsonPath.read(body, "$.apiKey.secret");
    merchantId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.merchantId"));
    var bulk = accountsService
        .open(merchantId, new OpenAccountCommand("Statement Export Bulk Holder"));
    var small = accountsService
        .open(merchantId, new OpenAccountCommand("Statement Export Small Holder"));
    bulkAccount = bulk.publicId();
    smallAccount = small.publicId();
    seedCreditLines(bulk.ledgerAccountPublicId(), SEEDED_BULK_LINES);
    seedCreditLines(small.ledgerAccountPublicId(), 3);
  }

  @Test
  void exportCarriesEveryLineAboveThePaginationCeiling() throws Exception {
    MvcResult result = mockMvc
        .perform(get("/v1/accounts/{id}/statement", bulkAccount)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String csv = result.getResponse().getContentAsString();
    String[] lines = csv.split("\r\n");
    // Composition header, values, blank separator, table header, then all 501
    // data rows — and nothing after them.
    assertEquals(505, lines.length);
    assertFalse(csv.contains("# truncated"), "a complete export must not carry the marker");

    // The composition section still opens the file whole: every seeded credit
    // is composed into the balance figure.
    assertEquals("balance,pendingIncoming,reservedOutgoing", lines[0]);
    assertEquals("501.0000,0,0", lines[1]);
    assertEquals("", lines[2]);

    // The postings table carries every line, newest first — the bulk seed's
    // backdated per-line instants make that order deterministic.
    assertEquals("bookedAt,transactionPublicId,memo,direction,amount,currency", lines[3]);
    for (int i = 0; i < SEEDED_BULK_LINES; i++) {
      String[] row = lines[4 + i].split(",", -1);
      assertEquals(6, row.length);
      assertEquals("bulk credit " + (SEEDED_BULK_LINES - 1 - i), row[2]);
      assertEquals("CREDIT", row[3]);
      assertEquals("1.0000", row[4]);
      assertEquals("BRL", row[5]);
    }
  }

  @Test
  void jsonStatementStillPagesAtItsOwnCeiling() throws Exception {
    mockMvc.perform(get("/v1/accounts/{id}/statement", bulkAccount)
            .header("Authorization", "Bearer " + merchantKey)
            .param("limit", "500"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.lines.length()").value(500));
  }

  @Test
  void subBoundExportCarriesItsRowsWithoutAMarker() throws Exception {
    MvcResult result = mockMvc
        .perform(get("/v1/accounts/{id}/statement", smallAccount)
            .header("Authorization", "Bearer " + merchantKey)
            .header("Accept", "text/csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.valueOf("text/csv;charset=UTF-8")))
        .andReturn();
    String csv = result.getResponse().getContentAsString();
    String[] lines = csv.split("\r\n");
    assertEquals(7, lines.length);
    assertFalse(csv.contains("# truncated"));
    assertEquals("3.0000,0,0", lines[1]);
    for (int i = 0; i < 3; i++) {
      String[] row = lines[4 + i].split(",", -1);
      assertEquals(6, row.length);
      assertEquals("CREDIT", row[3]);
      assertEquals("1.0000", row[4]);
      assertEquals("BRL", row[5]);
    }
  }

  /** Bulk-seeds {@code count} balanced two-posting transactions crediting the
   *  account — one CREDIT statement line each, in the funding settlement's
   *  shape (the clearing asset debited, the account credited) — straight into
   *  the journal, because routing 501 payments through the API would price the
   *  fixture out. Prepared statements reused in a loop over one connection and
   *  one commit: the deferred balance trigger only judges complete
   *  transactions, and the backdated, strictly increasing {@code booked_at}
   *  keeps newest-first ordering stable. */
  private static void seedCreditLines(UUID ledgerAccountPublicId, int count) throws SQLException {
    OffsetDateTime base = OffsetDateTime.ofInstant(Instant.now().minus(Duration.ofMinutes(60)),
        ZoneOffset.UTC);
    BigDecimal amount = new BigDecimal("1.0000");
    try (var c = adminConnection()) {
      c.setAutoCommit(false);
      long accountId;
      long clearingId;
      try (var lookup = c.prepareStatement(
          "select id from ledger.ledger_account where public_id = ?")) {
        accountId = internalId(lookup, ledgerAccountPublicId);
        clearingId = internalId(lookup, PaymentClearingAccount.PUBLIC_ID);
      }
      try (var tx = c.prepareStatement(
          "insert into ledger.journal_transaction (public_id, memo, booked_at) values (?, ?, ?) returning id");
          var posting = c.prepareStatement("""
              insert into ledger.journal_posting (transaction_id, account_id, direction, amount)
              values (?, ?, 'DEBIT', ?), (?, ?, 'CREDIT', ?)
              """)) {
        for (int i = 0; i < count; i++) {
          tx.setObject(1, UUID.randomUUID());
          tx.setString(2, "bulk credit " + i);
          tx.setObject(3, base.plusSeconds(i));
          long txId;
          try (ResultSet rs = tx.executeQuery()) {
            rs.next();
            txId = rs.getLong(1);
          }
          posting.setLong(1, txId);
          posting.setLong(2, clearingId);
          posting.setBigDecimal(3, amount);
          posting.setLong(4, txId);
          posting.setLong(5, accountId);
          posting.setBigDecimal(6, amount);
          posting.executeUpdate();
        }
        c.commit();
      }
    }
  }

  private static long internalId(PreparedStatement lookup, UUID publicId) throws SQLException {
    lookup.setObject(1, publicId);
    try (ResultSet rs = lookup.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
