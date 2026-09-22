package com.leandrossb.nummus.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.merchants.application.IssuedApiKey;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.testutils.ApiDrivers;
import com.leandrossb.nummus.testutils.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The M15 recording contract: merchant creation and the operator-key
 * lifecycle land in {@code audit.operator_action} attributed to the calling
 * key, inside the action's own transaction. Merchant self-serve key ops
 * record nothing; bootstrap attributes its entry to the key it mints.
 */
@TestPropertySource(properties = "nummus.operator.bootstrap-token=audit-bootstrap-token")
@AutoConfigureMockMvc
class KeyLifecycleAuditTest extends IntegrationTestBase {

  private static final String KEY = "Idempotency-Key";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private OperatorKeysService operatorKeys;

  /** (actorKey, action, subjectId) of the latest entries, newest first. */
  private List<String[]> recentEntries(int limit) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select actor_key::text, action, "
            + "coalesce(subject_id::text, '') from audit.operator_action "
            + "order by id desc limit " + limit)) {
      List<String[]> out = new ArrayList<>();
      while (rs.next()) {
        out.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
      }
      return out;
    }
  }

  private int entryCount() throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from audit.operator_action")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** The newest detail recorded for an action. */
  private String latestDetail(String action) throws Exception {
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select detail::text from audit.operator_action "
            + "where action = '" + action + "' order by id desc limit 1")) {
      Assertions.assertTrue(rs.next(), "expected a recorded " + action + " entry");
      return rs.getString(1);
    }
  }

  @Test
  void merchantCreationIsAuditedWithTheCallingKey() throws Exception {
    UUID callerId = operatorKeys.create("audit-caller", null, null).key().publicId();
    IssuedApiKey authKey = operatorKeys.create("audit-auth", null, callerId);
    MvcResult created = mockMvc.perform(post("/v1/merchants")
            .header("Authorization", "Bearer " + authKey.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Audited Merchant\"}"))
        .andExpect(status().isCreated()).andReturn();
    String merchantId = JsonPath.read(created.getResponse().getContentAsString(), "$.merchantId");
    var entries = recentEntries(1);
    Assertions.assertEquals(1, entries.size());
    Assertions.assertEquals("merchant.created", entries.get(0)[1]);
    Assertions.assertEquals(merchantId, entries.get(0)[2]);
    Assertions.assertEquals(authKey.key().publicId().toString(), entries.get(0)[0],
        "the entry is attributed to the calling key");
  }

  @Test
  void keyMintRotateAndRevokeAreAudited() throws Exception {
    var caller = operatorKeys.create("audit-actor", null, null);
    String callerId = caller.key().publicId().toString();

    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + caller.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"minted-probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String mintedId = JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    var top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.minted", top[1]);
    Assertions.assertEquals(callerId, top[0]);
    Assertions.assertEquals(mintedId, top[2]);

    MvcResult rotated = mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", "Bearer " + JsonPath.read(
                minted.getResponse().getContentAsString(), "$.secret"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated()).andReturn();
    String rotatedId = JsonPath.read(rotated.getResponse().getContentAsString(), "$.keyId");
    top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.rotated", top[1]);
    Assertions.assertEquals(mintedId, top[0], "the calling key is the actor");
    Assertions.assertEquals(rotatedId, top[2]);

    mockMvc.perform(delete("/v1/operator/api-keys/" + rotatedId)
            .header("Authorization", "Bearer " + caller.secret()))
        .andExpect(status().isNoContent());
    top = recentEntries(1).get(0);
    Assertions.assertEquals("operator_key.revoked", top[1]);
    Assertions.assertEquals(callerId, top[0]);
    Assertions.assertEquals(rotatedId, top[2]);
  }

  @Test
  void detailCarriesTheLabelAndRetiredKey() throws Exception {
    var caller = operatorKeys.create("detail-actor", null, null);
    MvcResult minted = mockMvc.perform(post("/v1/operator/api-keys")
            .header("Authorization", "Bearer " + caller.secret())
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"detail-probe\"}"))
        .andExpect(status().isCreated()).andReturn();
    String mintedId = JsonPath.read(minted.getResponse().getContentAsString(), "$.keyId");
    Assertions.assertTrue(latestDetail("operator_key.minted").contains("detail-probe"),
        "the mint's detail carries the label");

    mockMvc.perform(post("/v1/operator/api-keys/current/rotate")
            .header("Authorization", "Bearer " + JsonPath.read(
                minted.getResponse().getContentAsString(), "$.secret"))
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    String rotateDetail = latestDetail("operator_key.rotated");
    Assertions.assertTrue(rotateDetail.contains("detail-probe"), "the label carries forward");
    Assertions.assertTrue(rotateDetail.contains(mintedId), "the retired key is named");
  }

  @Test
  void merchantSelfServeKeyOpsRecordNothing() throws Exception {
    String merchant = ApiDrivers.createMerchantAndGetKey(mockMvc,
        ApiDrivers.operatorAuth(operatorKeys), "Unaudited Self");
    int before = entryCount();
    mockMvc.perform(post("/v1/me/api-keys")
            .header("Authorization", "Bearer " + merchant)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/v1/me/api-keys/current/rotate")
            .header("Authorization", "Bearer " + merchant)
            .header(KEY, UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated());
    Assertions.assertEquals(before, entryCount());
  }

  @Test
  void bootstrapRecordsWithTheNewKeyAsActor() throws Exception {
    revokeEveryActiveKey();
    var bootstrapped = operatorKeys.bootstrap("audit-bootstrap-token", "bootstrap-audit-probe");
    try (Connection c = adminConnection(); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("select actor_key::text, detail::text "
            + "from audit.operator_action where action = 'operator_key.bootstrapped' "
            + "order by id desc limit 1")) {
      Assertions.assertTrue(rs.next(), "bootstrap records its entry");
      Assertions.assertEquals(bootstrapped.key().publicId().toString(), rs.getString(1),
          "bootstrap attributes its entry to the key it just minted");
      Assertions.assertTrue(rs.getString(2).contains("bootstrap-audit-probe"));
    }
  }

  /** Bootstrap is one-time by table state, so this class alone decides when
   *  the one-time path runs — the shared-table sweep OperatorKeysServiceTest
   *  established. Sweep revocations are fixture hygiene and stay unattributed. */
  private void revokeEveryActiveKey() {
    operatorKeys.list().stream()
        .filter(key -> "ACTIVE".equals(key.status()))
        .forEach(key -> operatorKeys.revoke(key.publicId(), null));
  }
}
