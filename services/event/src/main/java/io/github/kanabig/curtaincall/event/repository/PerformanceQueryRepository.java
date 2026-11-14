package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.Filter;
import io.github.kanabig.curtaincall.event.dto.PerformanceQueryDtos.PerformanceItem;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * [EVT-4, EVT-5] 공연 조회. 판매 등록된 공연(sale_performance)만 대상이고 KOPIS 호출 없이 DB만 읽는다.
 * 목록은 시작일 오름차순이다.
 */
@Repository
public class PerformanceQueryRepository {

    private static final String FROM =
            " FROM sale_performance sp JOIN performance p ON p.mt20id = sp.mt20id";

    private final JdbcTemplate jdbc;

    public PerformanceQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 실행할 SQL과 인자. EXPLAIN 검증에도 쓴다. */
    public record Built(String sql, List<Object> args) {}

    public Built listQuery(Filter f, int page, int size, Instant now) {
        List<Object> args = new ArrayList<>();
        String where = where(f, now, args);
        args.add(size);
        args.add((long) page * size);
        return new Built(
                "SELECT sp.id, p.name, p.genre, p.area, p.state, p.start_date, p.end_date, p.facility_name, p.poster"
                        + FROM + where + " ORDER BY p.start_date, sp.id LIMIT ? OFFSET ?",
                args);
    }

    public Built countQuery(Filter f, Instant now) {
        List<Object> args = new ArrayList<>();
        String where = where(f, now, args);
        return new Built("SELECT COUNT(*)" + FROM + where, args);
    }

    private static String where(Filter f, Instant now, List<Object> args) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1");
        if (f.region() != null) {
            sb.append(" AND p.area = ?");
            args.add(f.region());
        }
        if (f.genre() != null) {
            sb.append(" AND p.genre = ?");
            args.add(f.genre());
        }
        // 기간과 겹치는 공연
        if (f.from() != null) {
            sb.append(" AND p.end_date >= ?");
            args.add(Date.valueOf(f.from()));
        }
        if (f.to() != null) {
            sb.append(" AND p.start_date <= ?");
            args.add(Date.valueOf(f.to()));
        }
        if (f.state() != null) {
            sb.append(" AND p.state = ?");
            args.add(f.state());
        }
        if (f.keyword() != null) {
            sb.append(" AND p.name LIKE ? ESCAPE '\\\\'");
            args.add("%" + escapeLike(f.keyword()) + "%");
        }
        if (f.maxPrice() != null) {
            // 판매할 수 있는 회차 중 최저 등급가가 maxPrice 이하인 회차가 하나라도 있는 공연
            sb.append(" AND EXISTS (SELECT 1 FROM session s JOIN session_price pr ON pr.session_id = s.id"
                    + " WHERE s.performance_id = sp.id AND s.suspended = FALSE AND s.start_at > ? AND pr.price <= ?)");
            args.add(Timestamp.from(now));
            args.add(f.maxPrice());
        }
        return sb.toString();
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public List<PerformanceItem> list(Filter f, int page, int size, Instant now) {
        Built q = listQuery(f, page, size, now);
        List<PerformanceItem> items = jdbc.query(
                q.sql(),
                (rs, i) -> new PerformanceItem(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("genre"),
                        rs.getString("area"),
                        rs.getString("state"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(),
                        rs.getString("facility_name"),
                        rs.getString("poster"),
                        null),
                q.args().toArray());
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, Integer> min = minPrices(items.stream().map(PerformanceItem::id).toList(), now);
        return items.stream()
                .map(i -> new PerformanceItem(
                        i.id(),
                        i.name(),
                        i.genre(),
                        i.area(),
                        i.state(),
                        i.startDate(),
                        i.endDate(),
                        i.facilityName(),
                        i.poster(),
                        min.get(i.id())))
                .toList();
    }

    public long count(Filter f, Instant now) {
        Built q = countQuery(f, now);
        return jdbc.queryForObject(q.sql(), Long.class, q.args().toArray());
    }

    /** 공연별로 판매할 수 있는 회차의 최저 등급가. 회차가 없으면 키가 없다. */
    private Map<Long, Integer> minPrices(List<Long> ids, Instant now) {
        List<Object> args = new ArrayList<>(ids);
        args.add(Timestamp.from(now));
        return jdbc
                .query(
                        "SELECT s.performance_id, MIN(pr.price) FROM session s"
                                + " JOIN session_price pr ON pr.session_id = s.id"
                                + " WHERE s.performance_id IN (" + "?,".repeat(ids.size() - 1) + "?)"
                                + " AND s.suspended = FALSE AND s.start_at > ? GROUP BY s.performance_id",
                        (rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2)),
                        args.toArray())
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public record Row(
            long id,
            String kopisId,
            String name,
            String genre,
            String area,
            String state,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            boolean openRun,
            String venueId,
            String facilityName,
            String poster,
            String runtime,
            String age,
            String priceGuidance,
            String dateGuidance,
            String relates) {}

    public Optional<Row> find(long id) {
        return jdbc
                .query(
                        "SELECT sp.id, p.mt20id, p.name, p.genre, p.area, p.state, p.start_date, p.end_date, p.open_run,"
                                + " p.mt13id, p.facility_name, p.poster, p.runtime, p.age, p.price_guidance,"
                                + " p.date_guidance, p.relates" + FROM + " WHERE sp.id = ?",
                        (rs, i) -> new Row(
                                rs.getLong("id"),
                                rs.getString("mt20id"),
                                rs.getString("name"),
                                rs.getString("genre"),
                                rs.getString("area"),
                                rs.getString("state"),
                                rs.getDate("start_date").toLocalDate(),
                                rs.getDate("end_date").toLocalDate(),
                                rs.getBoolean("open_run"),
                                rs.getString("mt13id"),
                                rs.getString("facility_name"),
                                rs.getString("poster"),
                                rs.getString("runtime"),
                                rs.getString("age"),
                                rs.getString("price_guidance"),
                                rs.getString("date_guidance"),
                                rs.getString("relates")),
                        id)
                .stream()
                .findFirst();
    }
}
