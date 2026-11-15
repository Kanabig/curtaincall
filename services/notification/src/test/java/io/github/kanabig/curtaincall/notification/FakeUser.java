package io.github.kanabig.curtaincall.notification;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** user의 /internal/users/{id}. {@code failures}만큼 500으로 실패한 뒤 정상 응답한다. */
final class FakeUser {

    private final HttpServer server;
    final AtomicInteger failures = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();

    FakeUser() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/internal/users/", exchange -> {
            requests.incrementAndGet();
            String id = exchange.getRequestURI().getPath().substring("/internal/users/".length());
            byte[] body;
            int status;
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                status = 500;
                body = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = ("{\"id\":" + id + ",\"email\":\"user" + id + "@example.com\",\"name\":\"tester\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        failures.set(0);
        requests.set(0);
    }
}
