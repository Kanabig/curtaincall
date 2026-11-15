package io.github.kanabig.curtaincall.event;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * KOPIS 모의 서버. 저장된 실제 응답(fixture)을 돌려주고, 요청을 기록하고, 실패(5xx, 지연, 깨진 응답)를 흉내 낸다. afterdate가 붙은 목록 요청은 기본으로 "바뀐 공연
 * 없음"(빈 목록)을 돌려준다.
 */
public final class FakeKopisServer {

    public static final String EMPTY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs></dbs>";

    private final HttpServer server;
    private final List<URI> requests = new CopyOnWriteArrayList<>();

    public volatile int status = 200;
    public volatile long delayMs;
    /** null이 아니면 모든 응답 대신 이 본문을 돌려준다. */
    public volatile String overrideBody;
    /** 이 공연 ID의 상세는 깨진 XML로 답한다. */
    public volatile String brokenDetailId;
    /** afterdate가 붙은 목록에도 이 본문을 돌려준다(바뀐 공연이 있는 상황). */
    public volatile String afterdateListBody;

    public FakeKopisServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/pblprfr", this::handle);
        server.createContext("/prfplc", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<URI> requests() {
        return requests;
    }

    public void reset() {
        requests.clear();
        status = 200;
        delayMs = 0;
        overrideBody = null;
        brokenDetailId = null;
        afterdateListBody = null;
    }

    public void stop() {
        server.stop(0);
    }

    public static String fixture(String name) {
        try (InputStream in = FakeKopisServer.class.getResourceAsStream("/kopis/fixture/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        requests.add(uri);
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            String body = respond(uri);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/xml;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private String respond(URI uri) {
        if (overrideBody != null) {
            return overrideBody;
        }
        String path = uri.getPath();
        String query = uri.getQuery() == null ? "" : uri.getQuery();
        String[] parts = path.split("/");
        if (path.startsWith("/prfplc/")) {
            return fixture("prfplc-" + parts[2] + ".xml");
        }
        if (parts.length >= 3) {
            if (parts[2].equals(brokenDetailId)) {
                return "<dbs><db><mt20id>" + parts[2] + "</mt20id>";
            }
            return fixture("pblprfr-" + parts[2] + ".xml");
        }
        if (query.contains("afterdate=")) {
            return afterdateListBody != null ? afterdateListBody : EMPTY;
        }
        return query.contains("cpage=1") ? fixture("pblprfr-list.xml") : EMPTY;
    }
}
