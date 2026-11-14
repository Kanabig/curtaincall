package io.github.kanabig.curtaincall.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;

/** 모든 서비스 경로를 받아 200을 돌려주는 스텁. 받은 요청 수만 센다. */
final class StubBackend {

    final HttpServer server;
    final AtomicInteger received = new AtomicInteger();

    StubBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                received.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void routeAllServicesHere(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + server.getAddress().getPort();
        for (String name : new String[] {"USER", "EVENT", "QUEUE", "RESERVATION", "SEARCH_ASSIST"}) {
            registry.add(name + "_SERVICE_URL", () -> url);
        }
    }
}
