package com.leandrossb.nummus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import com.leandrossb.nummus.accounts.application.AccountsService;
import com.leandrossb.nummus.accounts.domain.OpenAccountCommand;
import com.leandrossb.nummus.ledger.domain.Money;
import com.leandrossb.nummus.merchants.application.OperatorKeysService;
import com.leandrossb.nummus.payments.application.PaymentsService;
import com.leandrossb.nummus.payments.domain.CreateIntentCommand;
import com.leandrossb.nummus.psp_simulator.application.SimulatorService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** The state machines' throughput lands in the scrape: drive one lifecycle
 *  and read the counters back from Prometheus's exposition. */
@TestPropertySource(properties = "management.server.port=0")
class LifecycleMetricsTest extends ObservabilityTestBase {

  @Autowired
  private OperatorKeysService operatorKeys;

  @Autowired
  private AccountsService accountsService;

  @Autowired
  private PaymentsService payments;

  @Autowired
  private SimulatorService simulator;

  @Value("${local.management.port}")
  private int managementPort;

  @Value("${local.server.port}")
  private int apiPort;

  private UUID merchantId;

  @BeforeEach
  void createFixtures() {
    String operatorAuth = "Bearer " + operatorKeys.create("metrics-probe", null, null).secret();
    var headers = new HttpHeaders();
    headers.set("Authorization", operatorAuth);
    headers.set("Idempotency-Key", UUID.randomUUID().toString());
    headers.setContentType(MediaType.APPLICATION_JSON);
    var response = rest.postForEntity(
        "http://127.0.0.1:" + apiPort + "/v1/merchants",
        new HttpEntity<>("{\"name\":\"Lifecycle Metrics Merchant\"}", headers),
        String.class);
    assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
    merchantId = UUID.fromString(JsonPath.read(response.getBody(), "$.merchantId"));
  }

  private String scrape() {
    ResponseEntity<String> response =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus",
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  @Test
  void intentLifecycleIncrementsTheCounters() {
    var account = accountsService.open(merchantId, new OpenAccountCommand("Metrics Account"));
    var intent = payments.create(merchantId,
        new CreateIntentCommand(account.publicId(), Money.ofBrl("10.0000"), Duration.ofMinutes(10)));
    simulator.pay(intent.chargePublicId());
    payments.get(merchantId, intent.publicId());

    String body = scrape();
    assertTrue(body.contains("nummus_intents_total{outcome=\"created\"}"),
        "created counter missing from scrape");
    assertTrue(body.contains("nummus_intents_total{outcome=\"settled\"}"),
        "settled counter missing from scrape");
  }
}
