package com.leandrossb.nummus.interfaces.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Re-readable request wrapper: the filter consumed the body to fingerprint it. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  CachedBodyRequest(HttpServletRequest request, byte[] body) {
    super(request);
    this.body = body;
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream buffer = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override public boolean isFinished() { return buffer.available() == 0; }
      @Override public boolean isReady() { return true; }
      @Override public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException();
      }
      @Override public int read() { return buffer.read(); }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }
}
