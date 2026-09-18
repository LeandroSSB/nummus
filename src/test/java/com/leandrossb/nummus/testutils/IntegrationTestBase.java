package com.leandrossb.nummus.testutils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Base class for integration tests: shared PostgreSQL container and Spring context. */
@SpringBootTest
public abstract class IntegrationTestBase {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
      .withStartupTimeout(Duration.ofMinutes(3));

  static {
    POSTGRES.start();
  }

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
  }

  /** Superuser connection — the Flyway/owner role. Use for raw-SQL probes. */
  protected static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Password {@code LedgerRolesTest} grants to the {@code nummus_app} login in its {@code @BeforeAll}. */
  protected static final String APP_ROLE_PASSWORD = "nummus-app-test";

  /** Least-privileged application-role connection; {@code LedgerRolesTest} enables this
   * login in its {@code @BeforeAll}. */
  protected static Connection appConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "nummus_app", APP_ROLE_PASSWORD);
  }
}
