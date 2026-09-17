package com.leandrossb.nummus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.leandrossb.nummus.testutils.IntegrationTestBase;
import org.junit.jupiter.api.Test;

class ApplicationSmokeTest extends IntegrationTestBase {

  @Test
  void springContextStartsAndFlywayRuns() {
    assertDoesNotThrow(() -> {
      // Context startup already ran Flyway and applied the pending migrations.
      // Reaching here means datasource + Flyway wiring are correct.
    });
  }
}
