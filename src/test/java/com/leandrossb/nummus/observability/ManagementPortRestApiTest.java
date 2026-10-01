package com.leandrossb.nummus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Management traffic lives on its own port: probes and the Prometheus
 *  scrape answer there, and the merchant API port never serves actuator. */
@TestPropertySource(properties = "management.server.port=0")
class ManagementPortRestApiTest extends ObservabilityTestBase {

  @Value("${local.management.port}")
  private int managementPort;

  @Value("${local.server.port}")
  private int apiPort;

  @Test
  void livenessAndReadinessAnswerUpOnTheManagementPort() {
    ResponseEntity<String> liveness =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/liveness",
            String.class);
    assertEquals(HttpStatus.OK, liveness.getStatusCode());
    assertTrue(liveness.getBody().contains("UP"), liveness.getBody());
    ResponseEntity<String> readiness =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/readiness",
            String.class);
    assertEquals(HttpStatus.OK, readiness.getStatusCode());
    assertTrue(readiness.getBody().contains("UP"), readiness.getBody());
  }

  @Test
  void prometheusScrapeAnswersOnTheManagementPort() {
    ResponseEntity<String> scrape =
        rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus",
            String.class);
    assertEquals(HttpStatus.OK, scrape.getStatusCode());
    assertTrue(scrape.getBody().contains("# TYPE"));
  }

  @Test
  void mainApiPortNeverServesActuator() {
    assertNotEquals(apiPort, managementPort);
    ResponseEntity<String> leaked =
        rest.getForEntity("http://127.0.0.1:" + apiPort + "/actuator/health", String.class);
    assertEquals(HttpStatus.NOT_FOUND, leaked.getStatusCode());
  }
}
