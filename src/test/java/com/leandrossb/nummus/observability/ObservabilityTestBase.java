package com.leandrossb.nummus.observability;

import java.time.Duration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Boots a real server pair (API + management) for the observability surface:
 *  the shared MockMvc base has no ports to probe. Own container — the context
 *  differs, so sharing IntegrationTestBase's cached one is not possible. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ObservabilityTestBase {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
      // Each cached Spring context holds a Hikari pool; at the image default of
      // 100 connections the suite runs out of client slots before it runs out of
      // tests. Keep generous headroom for the raw DriverManager probes too.
      .withCommand("postgres", "-c", "max_connections=300")
      .withStartupTimeout(Duration.ofMinutes(3));

  static {
    POSTGRES.start();
  }

  protected final TestRestTemplate rest = new TestRestTemplate();

  @DynamicPropertySource
  static void registerDataSource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    // No integration-test context may run the delivery scheduler against the network.
    registry.add("nummus.webhooks.poll-delay-ms", () -> "3600000");
    registry.add("nummus.webhooks.initial-delay-ms", () -> "3600000");
    // Nor the conciliation scheduler: ConciliationWorkerTest drives the tick by hand.
    registry.add("nummus.conciliation.poll-delay-ms", () -> "3600000");
    registry.add("nummus.conciliation.initial-delay-ms", () -> "3600000");
  }
}
