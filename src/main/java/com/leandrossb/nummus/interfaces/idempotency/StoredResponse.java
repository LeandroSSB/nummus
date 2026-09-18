package com.leandrossb.nummus.interfaces.idempotency;

/** A serialized first-execution response, replayed verbatim on retries. */
public record StoredResponse(int status, String contentType, String location, String body) {
}
