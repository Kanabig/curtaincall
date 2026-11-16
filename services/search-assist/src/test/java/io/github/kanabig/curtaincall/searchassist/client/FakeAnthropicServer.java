package io.github.kanabig.curtaincall.searchassist.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Messages API를 흉내 내는 로컬 HTTP 서버. 응답 본문·지연·상태를 테스트가 정한다. */
public class FakeAnthropicServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastApiKey = new AtomicReference<>("");
    private volatile int status = 200;
    private volatile String body = message("{}");
    private volatile long delayMillis;

    public FakeAnthropicServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastApiKey.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 먼저 끊은 경우
            }
        });
        // 타임아웃으로 끊긴 지연 요청이 다음 요청을 막지 않게 한다
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 모델이 text 블록으로 돌려준 것처럼 감싼 정상 응답. */
    public static String message(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"id":"msg_test","type":"message","role":"assistant","model":"test",\
                "content":[{"type":"text","text":"%s"}],"stop_reason":"end_turn","stop_sequence":null,\
                "usage":{"input_tokens":10,"output_tokens":10}}""".formatted(escaped);
    }

    public void replyText(String text) {
        reply(200, message(text), 0);
    }

    public void reply(int status, String body, long delayMillis) {
        this.status = status;
        this.body = body;
        this.delayMillis = delayMillis;
    }

    public int requests() {
        return requests.get();
    }

    public String lastBody() {
        return lastBody.get();
    }

    public String lastApiKey() {
        return lastApiKey.get();
    }

    public void reset() {
        requests.set(0);
        reply(200, message("{}"), 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
