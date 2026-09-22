package com.leandrossb.nummus.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.leandrossb.nummus.conciliation.application.ConciliationProperties;
import com.leandrossb.nummus.interfaces.HttpProperties;
import com.leandrossb.nummus.interfaces.ratelimit.RateLimitProperties;
import com.leandrossb.nummus.merchants.application.ApiKeyProperties;
import com.leandrossb.nummus.webhooks.application.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** Degenerate configuration fails startup — one binding-error per knob,
 *  tested against the properties infrastructure alone (no containers). */
class PropertiesValidationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
          ValidationAutoConfiguration.class))
      .withUserConfiguration(AllProperties.class);

  @Configuration
  @EnableConfigurationProperties({WebhookProperties.class, RateLimitProperties.class,
      HttpProperties.class, ApiKeyProperties.class, ConciliationProperties.class})
  static class AllProperties {
  }

  private void failsWith(String... pairs) {
    runner.withPropertyValues(pairs).run(context -> {
      assertTrue(context.getStartupFailure() != null,
          "boot must fail for " + String.join(",", pairs));
      assertTrue(context.getStartupFailure().getMessage().contains("Could not bind properties")
          || context.getStartupFailure().getMessage().contains("Binding to target")
          || context.getStartupFailure().getMessage().contains("failed"),
          "the failure must be a binding/validation error: "
              + context.getStartupFailure().getMessage());
    });
  }

  @Test
  void webhookKnobsAreValidated() {
    failsWith("nummus.webhooks.max-attempts=0");
    failsWith("nummus.webhooks.backoff-base=PT0S");
    failsWith("nummus.webhooks.batch-size=0");
    failsWith("nummus.webhooks.retention-days=-1");
  }

  @Test
  void rateLimitKnobsAreValidated() {
    failsWith("nummus.ratelimit.merchant-capacity=0");
    failsWith("nummus.ratelimit.merchant-refill-per-second=-1");
    failsWith("nummus.ratelimit.operator-capacity=-3");
    failsWith("nummus.ratelimit.operator-refill-per-second=-2");
  }

  @Test
  void httpKnobsAreValidatedIncludingTheOverflowEdge() {
    failsWith("nummus.http.max-body-bytes=0");
    failsWith("nummus.http.max-body-bytes=2147483647");
  }

  @Test
  void apiKeyKnobsAreValidated() {
    failsWith("nummus.api-keys.rotation-grace=PT0S");
  }

  @Test
  void conciliationKnobsAreValidated() {
    failsWith("nummus.conciliation.poll-delay-ms=0");
    failsWith("nummus.conciliation.initial-delay-ms=-5");
    failsWith("nummus.conciliation.window-lag=PT-1S");
    failsWith("nummus.conciliation.max-window-ahead=PT0S");
  }

  @Test
  void legalEdgesBootFine() {
    runner.withPropertyValues(
            "nummus.conciliation.window-lag=PT0S",
            "nummus.ratelimit.operator-refill-per-second=0",
            "nummus.webhooks.retention-days=0")
        .run(context -> assertNull(context.getStartupFailure(), "boot must succeed"));
  }
}
