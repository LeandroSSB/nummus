package com.leandrossb.nummus.conciliation.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** Instants travel as ISO-8601 strings; parsing and from<to live in the service (400s). */
public record CreateReportRequest(
    @NotBlank(message = "from must not be blank") String from,
    @NotBlank(message = "to must not be blank") String to) {
}
