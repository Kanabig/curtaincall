package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.event.dto.PriceTable;
import io.github.kanabig.curtaincall.event.dto.SeatMapEntry;
import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.entity.SaleSession.Price;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository.SeatRow;
import io.github.kanabig.curtaincall.event.repository.SessionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * EVT-9, EVT-10. 회차 좌석 배치도를 만들어 직렬화한 응답을 캐시한다. 배치도는 회차가 판매되는 동안 바뀌지 않으므로(가격표는 오픈 10분 전까지만 바뀐다) 같은 회차를 다시 요청하면 DB를
 * 읽지 않는다. 회차를 수정·삭제하면 커밋 뒤 캐시를 비우고, 인스턴스가 여럿이어도 {@link #TTL} 안에는 맞는다.
 *
 * <p>좌석 등급은 가격표를 비싼 순으로 늘어놓고 좌석의 depth(무대에서 먼 정도)로 나눠 정한다.
 */
@Service
public class SeatMapService {

    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_ENTRIES = 16;
    static final Duration LOAD_WAIT = Duration.ofSeconds(10);

    private final SessionRepository sessions;
    private final HallSeatRepository seats;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private final Map<Long, Loading> loadings = new ConcurrentHashMap<>();

    public SeatMapService(SessionRepository sessions, HallSeatRepository seats, ObjectMapper mapper, Clock clock) {
        this.sessions = sessions;
        this.seats = seats;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 직렬화한 JSON 본문과 ETag. 회차가 없으면 SESSION_NOT_FOUND. */
    public Body get(long sessionId) {
        Cached hit = cache.get(sessionId);
        if (hit != null && clock.instant().isBefore(hit.expiresAt())) {
            return hit.body();
        }
        Loading loading = new Loading();
        Loading running = loadings.putIfAbsent(sessionId, loading);
        if (running != null) {
            return running.await(sessionId);
        }
        try {
            Body body = load(sessionId);
            store(sessionId, loading, body);
            loading.future.complete(body);
            return body;
        } catch (RuntimeException | Error e) {
            loading.future.completeExceptionally(e);
            throw e;
        } finally {
            loadings.remove(sessionId, loading);
        }
    }

    /** 로딩 중에 evict가 끼어들었으면(invalidated) 옛 값이므로 캐시에 넣지 않는다. 대기자에게는 그대로 돌려준다. */
    private void store(long sessionId, Loading loading, Body body) {
        synchronized (loading) {
            if (loading.invalidated) {
                return;
            }
            if (!cache.containsKey(sessionId) && cache.size() >= MAX_ENTRIES) {
                cache.entrySet().stream()
                        .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                        .ifPresent(e -> cache.remove(e.getKey()));
            }
            cache.put(sessionId, new Cached(body, clock.instant().plus(TTL)));
        }
    }

    /** 회차를 바꾼 트랜잭션이 커밋된 뒤에 비운다. 커밋 전에 비우면 그 사이 읽은 옛 값이 다시 캐시에 남는다. */
    public void evictAfterCommit(long sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(sessionId);
            }
        });
    }

    private void evict(long sessionId) {
        Loading loading = loadings.remove(sessionId);
        if (loading != null) {
            synchronized (loading) {
                loading.invalidated = true;
            }
        }
        cache.remove(sessionId);
    }

    private Body load(long sessionId) {
        SaleSession session =
                sessions.find(sessionId).orElseThrow(() -> new BusinessException(EventErrorCode.SESSION_NOT_FOUND));
        List<Price> byPrice = session.prices().stream()
                .sorted(Comparator.comparingInt(Price::price).reversed().thenComparing(Price::grade))
                .toList();
        PriceTable table = new PriceTable(
                byPrice.stream()
                        .map(p -> new PriceTable.Tier(p.grade(), p.price()))
                        .toList(),
                true);
        List<SeatRow> rows = seats.findSeatRows(session.venueId());
        List<SeatMapEntry> entries = new ArrayList<>(rows.size());
        for (SeatRow row : rows) {
            var spec = row.spec();
            PriceTable.Tier tier = table.tierAt(spec.depth());
            entries.add(new SeatMapEntry(
                    row.seatId(),
                    spec.floor(),
                    spec.section(),
                    spec.row(),
                    spec.number(),
                    spec.x(),
                    spec.y(),
                    tier.grade(),
                    tier.price(),
                    spec.wheelchair()));
        }
        try {
            byte[] json = mapper.writeValueAsBytes(entries);
            String etag = etag(json);
            return new Body(json, etag, gzip(json), etag.substring(0, etag.length() - 1) + "-gzip\"");
        } catch (IOException e) {
            throw new IllegalStateException("좌석 배치도를 직렬화하지 못했다 sessionId=" + sessionId, e);
        }
    }

    private static String etag(byte[] json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] gzip(byte[] json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length / 8);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(json);
        }
        return out.toByteArray();
    }

    /** 본문을 평문과 gzip 두 가지로 들고 있다. 압축은 만들 때 한 번만 하고, 인코딩마다 ETag가 다르다. */
    public record Body(byte[] json, String etag, byte[] gzip, String gzipEtag) {}

    /** 회차별로 진행 중인 로딩. 먼저 온 요청이 DB를 읽고, 나머지는 그 결과(또는 실패)를 받는다. */
    private static final class Loading {
        final CompletableFuture<Body> future = new CompletableFuture<>();
        boolean invalidated;

        Body await(long sessionId) {
            try {
                return future.get(LOAD_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new IllegalStateException("좌석 배치도 로딩 실패 sessionId=" + sessionId, e.getCause());
            } catch (TimeoutException e) {
                throw new IllegalStateException("좌석 배치도 로딩 대기 초과 sessionId=" + sessionId, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("좌석 배치도 로딩 대기 중단 sessionId=" + sessionId, e);
            }
        }
    }

    private record Cached(Body body, Instant expiresAt) {}
}
