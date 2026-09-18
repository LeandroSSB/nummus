package com.leandrossb.nummus.webhooks.infrastructure;

import com.leandrossb.nummus.webhooks.application.DeliveryResult;
import com.leandrossb.nummus.webhooks.application.EventDeliveryClient;
import com.leandrossb.nummus.webhooks.application.SignatureHeaders;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Production HTTP delivery: the exact stored payload bytes, signed, with tight timeouts. */
@Component
public class RestClientEventDeliveryClient implements EventDeliveryClient {

  private final RestClient restClient;

  public RestClientEventDeliveryClient(RestClient.Builder builder) {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
    this.restClient = builder.requestFactory(factory).build();
  }

  @Override
  public DeliveryResult deliver(URI url, String secret, String eventType, String payload) {
    try {
      var response = restClient.post().uri(url)
          .contentType(MediaType.APPLICATION_JSON)
          .header("Nummus-Signature", SignatureHeaders.sign(secret, payload, Instant.now()))
          .header("Nummus-Event", eventType)
          .body(payload)
          .retrieve()
          .toBodilessEntity();
      return new DeliveryResult(response.getStatusCode().is2xxSuccessful(), response.getStatusCode().value());
    } catch (RestClientResponseException e) {
      return new DeliveryResult(false, e.getStatusCode().value());
    } catch (ResourceAccessException e) {
      return new DeliveryResult(false, null); // connect/read failure or timeout
    }
  }
}
