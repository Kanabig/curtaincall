package io.github.kanabig.curtaincall.payment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * mock-pg의 계약(승인, 거래 조회, 장애 모드)을 따르는 테스트용 PG. 승인 장애는 mock-pg와 같은 순서로 적용한다: 지연 → 타임아웃(기록하지 않고 대기 후 504) → 실패(500, 기록
 * 안 함) → 기록 → 응답 유실(헤더만 보내고 연결 끊김). 같은 merchantOrderId 재승인은 첫 거래를 돌려준다.
 */
final class FakePg {

    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private final HttpServer server;
    private final Map<String, Transaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> approvalRequests = new ConcurrentHashMap<>();

    volatile long delayMs;
    volatile boolean hang;
    volatile boolean fail;
    volatile boolean dropResponse;
    volatile long hangMs = 60_000;
    volatile boolean inquiryFail;
    volatile boolean cancelFail;
    private final java.util.Set<String> preCancelled = ConcurrentHashMap.newKeySet();
    private final AtomicInteger cancelRequests = new AtomicInteger();

    private record Transaction(String tid, String merchantOrderId, String status, long amount, Instant createdAt) {}

    FakePg() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/pg/v1/approvals", this::approve);
        server.createContext("/pg/v1/transactions", this::transactions);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        delayMs = 0;
        hang = false;
        fail = false;
        dropResponse = false;
        inquiryFail = false;
        cancelFail = false;
        transactions.clear();
        approvalRequests.clear();
        preCancelled.clear();
        cancelRequests.set(0);
    }

    /** 승인 요청이 PG에 도착한 횟수(기록 여부와 무관). */
    int approvalRequestsFor(String merchantOrderId) {
        AtomicInteger count = approvalRequests.get(merchantOrderId);
        return count == null ? 0 : count.get();
    }

    int totalApprovalRequests() {
        return approvalRequests.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /** PG에는 기록이 있는데 payment는 모르는 상황을 만든다. */
    void seed(String merchantOrderId, long amount) {
        transactions.put(
                merchantOrderId,
                new Transaction("mpg_seed_" + merchantOrderId, merchantOrderId, "APPROVED", amount, Instant.now()));
    }

    int cancelRequests() {
        return cancelRequests.get();
    }

    boolean isPreCancelled(String merchantOrderId) {
        return preCancelled.contains(merchantOrderId);
    }

    boolean hasTransaction(String merchantOrderId) {
        return transactions.containsKey(merchantOrderId);
    }

    private void approve(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String orderId = stringField(body, "merchantOrderId");
        String cardToken = stringField(body, "cardToken");
        long amount = Long.parseLong(numberField(body, "amount"));
        approvalRequests.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (hang) {
                Thread.sleep(hangMs);
                respond(exchange, 504, "{}");
                return;
            }
            if (!"tok_approve".equals(cardToken) && !"tok_decline".equals(cardToken)) {
                respond(exchange, 400, "{}");
                return;
            }
            if (fail) {
                respond(exchange, 500, "{}");
                return;
            }
            if (preCancelled.contains(orderId)) {
                respond(exchange, 409, "{}");
                return;
            }
            Transaction candidate = new Transaction(
                    "mpg_" + Math.abs(orderId.hashCode()),
                    orderId,
                    "tok_decline".equals(cardToken) ? "DECLINED" : "APPROVED",
                    amount,
                    Instant.now());
            Transaction existing = transactions.putIfAbsent(orderId, candidate);
            Transaction result = existing != null ? existing : candidate;
            if (dropResponse) {
                // 본문 없이 큰 Content-Length만 보내고 닫는다. 클라이언트는 premature EOF를 만난다
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().flush();
                exchange.close();
                return;
            }
            respond(exchange, 200, json(result, existing != null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 클라이언트가 먼저 끊었다
        } finally {
            exchange.close();
        }
    }

    private void transactions(HttpExchange exchange) throws IOException {
        if ("POST".equals(exchange.getRequestMethod())) {
            cancelByOrder(exchange);
        } else {
            find(exchange);
        }
    }

    /** mock-pg의 망취소: 거래가 없으면 사전 취소로 기록해 이후 승인을 거절한다. */
    private void cancelByOrder(HttpExchange exchange) throws IOException {
        cancelRequests.incrementAndGet();
        String query = exchange.getRequestURI().getRawQuery();
        String orderId = URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
        if (cancelFail) {
            respond(exchange, 500, "{}");
        } else {
            if (!transactions.containsKey(orderId)) {
                preCancelled.add(orderId);
            }
            respond(exchange, 200, "{\"status\":\"CANCELLED\"}");
        }
        exchange.close();
    }

    private void find(HttpExchange exchange) throws IOException {
        if (inquiryFail) {
            respond(exchange, 500, "{}");
            exchange.close();
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String orderId = URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
        Transaction transaction = transactions.get(orderId);
        if (transaction == null) {
            respond(exchange, 404, "{\"status\":404}");
        } else {
            respond(exchange, 200, json(transaction, false));
        }
        exchange.close();
    }

    private static String json(Transaction t, boolean duplicate) {
        String failureCode = "DECLINED".equals(t.status()) ? "\"CARD_DECLINED\"" : "null";
        return "{\"tid\":\"%s\",\"merchantOrderId\":\"%s\",\"status\":\"%s\",\"amount\":%d,\"failureCode\":%s,\"createdAt\":\"%s\",\"duplicate\":%s}"
                .formatted(t.tid(), t.merchantOrderId(), t.status(), t.amount(), failureCode, t.createdAt(), duplicate);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String stringField(String json, String name) {
        Matcher matcher =
                Pattern.compile(STRING_FIELD.pattern().formatted(name)).matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException(name + " 없음: " + json);
        }
        return matcher.group(1);
    }

    private static String numberField(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException(name + " 없음: " + json);
        }
        return matcher.group(1);
    }
}
