package com.leandrossb.nummus.webhooks;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test receiver: a JDK HttpServer on an ephemeral port capturing every request. */
final class ReceiverServer implements AutoCloseable {

  record Received(String method, String path, Map<String, String> headers, String body) {
  }

  final List<Received> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
  private final HttpServer server;

  ReceiverServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (InputStream body = exchange.getRequestBody()) {
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
        requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            headers, new String(body.readAllBytes(), StandardCharsets.UTF_8)));
      }
      Integer status = statusByPath.getOrDefault(exchange.getRequestURI().getPath(), 200);
      byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, response.length);
      try (var out = exchange.getResponseBody()) {
        out.write(response);
      }
    });
    server.start();
  }

  void respondWith(String path, int status) {
    statusByPath.put(path, status);
  }

  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
