package com.leandrossb.nummus.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The operator audit log over REST: newest-first keyset pagination with a
 * {@code Next-Cursor}, the exact-match action filter, and operator gating.
 * Entries are seeded through the audited services themselves (chained key
 * mints, merchant creation) — never written over HTTP.
 */
@AutoConfigureMockMvc
class AuditLogRestApiTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  /** The shared container accumulates {@code operator_key.minted} entries
   *  from every suite that mints over HTTP; this walk must terminate exactly
   *  at the oldest of its own three entries, so stale minted rows are swept
   *  first — the same shared-table sweep OperatorKeysServiceTest established.
   *  Every minted-entry read in the other suites reads the newest row after
   *  its own mint, so the sweep is invisible to them. */
  private void sweepMintedEntries() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement()) {
      st.executeUpdate("delete from audit.operator_action where action = 'operator_key.minted'");
    }
  }

  @Test
  void auditLogPaginatesNewestFirst() throws Exception {
    sweepMintedEntries();
    // Chained mints: each key mints the next, so the three operator_key.minted
    // entries carry distinct actor labels and distinct minted labels.
    var seed = operatorKeys.create("walk-seed", null, null); // actor null: no entry
    var first = operatorKeys.create("walk-first", null, seed.key().publicId());
    var second = operatorKeys.create("walk-second", null, first.key().publicId());
    var third = operatorKeys.create("walk-third", null, second.key().publicId());
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    MvcResult page1 = mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", auth)
            .param("action", "operator_key.minted").param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(header().exists("Next-Cursor"))
        .andExpect(jsonPath("$[0].entryId").exists())
        .andExpect(jsonPath("$[0].actorKey").value(second.key().publicId().toString()))
        .andExpect(jsonPath("$[0].actorLabel").value("walk-second"))
        .andExpect(jsonPath("$[0].subjectType").value("operator_key"))
        .andExpect(jsonPath("$[0].detail.label").value("walk-third"))
        .andExpect(jsonPath("$[0].occurredAt").exists())
        .andReturn();
    String cursor = page1.getResponse().getHeader("Next-Cursor");
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", auth)
            .param("action", "operator_key.minted").param("limit", "2").param("after", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].detail.label").value("walk-first"))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void actionFilterNarrows() throws Exception {
    // The merchant first, then a mint: unfiltered, the mint would be the
    // newest entry — the filter must hand back only merchant.created rows,
    // with this class's creation on top.
    var actor = operatorKeys.create("filter-actor", null, null);
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + actor.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Audit Filter Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantId = JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
    var minted = operatorKeys.create("filter-minted", null,
        actor.key().publicId()); // newer than the creation
    // Unfiltered first — the null-action listing branch with rows on the page:
    // the newest entry overall must be this class's just-created mint.
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .param("limit", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").value("operator_key.minted"))
        .andExpect(jsonPath("$[0].subjectId").value(minted.key().publicId().toString()))
        .andExpect(jsonPath("$[0].subjectType").value("operator_key"))
        .andExpect(jsonPath("$[0].actorKey").value(actor.key().publicId().toString()));
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .param("action", "merchant.created"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].action").value("merchant.created"))
        .andExpect(jsonPath("$[0].subjectId").value(merchantId))
        .andExpect(jsonPath("$[0].actorLabel").value("filter-actor"))
        .andExpect(jsonPath("$[0].detail.name").value("Audit Filter Merchant"))
        .andExpect(jsonPath("$[?(@.action != 'merchant.created')]").isEmpty());
  }

  @Test
  void boundsAndUnknownCursor() throws Exception {
    String auth = ApiDrivers.operatorAuth(operatorKeys);
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", auth).param("limit", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", auth).param("limit", "101"))
        .andExpect(status().isBadRequest());
    // An unknown cursor anchors past every row: an empty page, terminal (no
    // Next-Cursor). With no action param this also drives the null-action path.
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", auth).param("after", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0))
        .andExpect(header().doesNotExist("Next-Cursor"));
  }

  @Test
  void merchantKeysGet403() throws Exception {
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", "Bearer " + ApiDrivers.createMerchantAndGetKey(
                mockMvc, ApiDrivers.operatorAuth(operatorKeys), "Audit Gated Merchant")))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/v1/operator/audit-log"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void detailRendersAsAnObject() throws Exception {
    var actor = operatorKeys.create("detail-object", null, null);
    operatorKeys.create("detail-object-minted", null, actor.key().publicId());
    // The newest minted entry is this class's own, whatever else accumulated.
    mockMvc.perform(get("/v1/operator/audit-log")
            .header("Authorization", ApiDrivers.operatorAuth(operatorKeys))
            .param("action", "operator_key.minted").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].detail.label").exists())
        .andExpect(jsonPath("$[0].detail.label").value("detail-object-minted"));
  }
}
