package com.leandrossb.nummus.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.leandrossb.nummus.interfaces.HttpProperties;
import com.leandrossb.nummus.interfaces.idempotency.IdempotencyWebFilter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RequestBodyCapTest {

  private static final int CAP = 16;

  private final IdempotencyWebFilter filter =
      new IdempotencyWebFilter(JsonMapper.builder().build(), new HttpProperties(CAP));

  /** A stream that fails the test if anything reads from it. */
  private static ServletInputStream unreadable() {
    return new ServletInputStream() {
      @Override public int read() {
        throw new AssertionError("the oversized body must not be read");
      }
      @Override public boolean isFinished() { return true; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) { }
    };
  }

  private static ServletInputStream streamOf(String body) {
    ByteArrayInputStream source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    return new ServletInputStream() {
      @Override public int read() { return source.read(); }
      @Override public boolean isFinished() { return source.available() == 0; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) { }
    };
  }

  private static MockHttpServletRequest post(String uri, long contentLength, ServletInputStream body) {
    // Spring Framework 7's mock has no declared-length setter and no stream
    // setter, so the two are decoupled by overriding the accessors.
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
      @Override public long getContentLengthLong() { return contentLength; }
      @Override public ServletInputStream getInputStream() { return body; }
    };
    request.addHeader("Idempotency-Key", "cap-probe");
    request.setContentType(MediaType.APPLICATION_JSON_VALUE);
    return request;
  }

  private static MockHttpServletResponse run(IdempotencyWebFilter filter,
      MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (ServletRequest req, ServletResponse res) -> {
      res.setContentType("text/plain"); // reached only when the request passes
    });
    return response;
  }

  @Test
  void oversizedContentLengthIsRefusedWithoutReading() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", CAP + 1, unreadable());
    MockHttpServletResponse response = run(filter, request);
    assertEquals(413, response.getStatus());
    assertEquals("application/problem+json", response.getContentType());
  }

  @Test
  void lyingContentLengthIsStoppedByTheBoundedRead() throws Exception {
    // Claims 4 bytes, actually 40 — the bounded read is the backstop.
    MockHttpServletRequest request = post("/v1/probe", 4,
        streamOf("x".repeat(40)));
    assertEquals(413, run(filter, request).getStatus());
  }

  @Test
  void absentContentLengthIsCappedByTheBoundedRead() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", -1,
        streamOf("x".repeat(40)));
    assertEquals(413, run(filter, request).getStatus());
  }

  @Test
  void atCapBodyPassesThrough() throws Exception {
    MockHttpServletRequest request = post("/v1/probe", CAP, streamOf("x".repeat(CAP)));
    assertEquals(200, run(filter, request).getStatus());
  }
}
