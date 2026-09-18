package com.leandrossb.nummus.webhooks.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * Request body for registering a webhook endpoint. Event types validate against the
 * payments catalog. {@code url} is a String because bean validation constraints
 * (@NotBlank/@Pattern) have no validator for {@link java.net.URI}; the controller
 * parses the validated value.
 */
public record CreateEndpointRequest(
    @NotBlank(message = "url must not be blank")
    @Pattern(regexp = "^https?://.+", message = "url must be an http(s) URL") String url,
    List<String> eventTypes) {
}
