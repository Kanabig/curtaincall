package io.github.kanabig.curtaincall.event.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private final SessionRepository sessions;
    private final HallSeatRepository seats;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public SeatMapService(SessionRepository sessions, HallSeatRepository seats, ObjectMapper mapper, Clock clock) {
        this.sessions = sessions;
        this.seats = seats;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 직렬화한 JSON 본문과 ETag. 회차가 없으면 SESSION_NOT_FOUND. */
    public Body get(long sessionId) {
        Instant now = clock.instant();
        Cached hit = cache.get(sessionId);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return hit.body();
        }
        Body body = load(sessionId);
        if (cache.size() >= MAX_ENTRIES) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
        cache.put(sessionId, new Cached(body, now.plus(TTL)));
        return body;
    }

    /** 회차를 바꾼 트랜잭션이 커밋된 뒤에 비운다. 커밋 전에 비우면 그 사이 읽은 옛 값이 다시 캐시에 남는다. */
    public void evictAfterCommit(long sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.remove(sessionId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.remove(sessionId);
            }
        });
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

    private record Cached(Body body, Instant expiresAt) {}
}
