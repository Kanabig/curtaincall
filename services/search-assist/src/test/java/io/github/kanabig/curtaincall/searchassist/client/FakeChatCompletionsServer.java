package io.github.kanabig.curtaincall.searchassist.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** OpenAI 호환 /chat/completions를 흉내 내는 로컬 HTTP 서버. 응답 본문·지연·상태를 테스트가 정한다. */
public class FakeChatCompletionsServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private volatile int status = 200;
    private volatile String body = completion("{}");
    private volatile long delayMillis;

    public FakeChatCompletionsServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastPath.set(exchange.getRequestURI().getPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthorization.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
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

    /** 모델이 content로 돌려준 것처럼 감싼 정상 응답. */
    public static String completion(String content) {
        return completion(content, null);
    }

    /** reasoning_content(추론 출력)를 함께 돌려주는 응답. 추출기는 이 값을 읽지 않아야 한다. */
    public static String completion(String content, String reasoning) {
        String reasoningField = reasoning == null ? "" : ",\"reasoning_content\":" + quote(reasoning);
        return """
                {"id":"chatcmpl-test","object":"chat.completion","model":"test",\
                "choices":[{"index":0,"message":{"role":"assistant","content":%s%s},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".formatted(quote(content), reasoningField);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    public void replyContent(String content) {
        reply(200, completion(content), 0);
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

    public String lastAuthorization() {
        return lastAuthorization.get();
    }

    public String lastPath() {
        return lastPath.get();
    }

    public void reset() {
        requests.set(0);
        reply(200, completion("{}"), 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
