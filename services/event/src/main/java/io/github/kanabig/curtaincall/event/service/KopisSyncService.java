package io.github.kanabig.curtaincall.event.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.event.client.KopisCallException;
import io.github.kanabig.curtaincall.event.client.KopisClient;
import io.github.kanabig.curtaincall.event.client.KopisParseException;
import io.github.kanabig.curtaincall.event.client.KopisXmlParser;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import io.github.kanabig.curtaincall.event.dto.KopisFacility;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceDetail;
import io.github.kanabig.curtaincall.event.entity.PendingPerformance;
import io.github.kanabig.curtaincall.event.entity.SyncRun;
import io.github.kanabig.curtaincall.event.entity.SyncStatus;
import io.github.kanabig.curtaincall.event.repository.KopisCallLogRepository;
import io.github.kanabig.curtaincall.event.repository.KopisSyncLockRepository;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository;
import io.github.kanabig.curtaincall.event.repository.SyncRunRepository;
import io.github.kanabig.curtaincall.event.repository.VenueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * EVT-1, EVT-3. KOPIS 증분 동기화. 목록(앞으로 windowDays일) → 상세(처음 보거나 바뀐 공연) → 시설상세(처음 보는 시설, 오래된 시설) 순서다.
 *
 * <p>저장은 모두 ID 기준 upsert라 같은 동기화를 두 번 돌려도 결과가 같고, 중간에 멈춰도 이미 저장한 것은 남는다. 호출 상한에 닿으면 멈추고, 상세를 못 받은 공연은
 * PENDING으로 남아 다음 실행이 이어 받는다. KOPIS 호출이 실패하면 즉시 재시도하지 않고 그 실행을 끝낸다(재시도도 한도를 쓴다).
 */
@Service
public class KopisSyncService {

    private static final Logger log = LoggerFactory.getLogger(KopisSyncService.class);
    private static final String LOCK = "kopis-sync";
    /** KOPIS 공연목록 조회는 시작일~종료일이 최대 31일이다(공식 가이드). */
    private static final int MAX_WINDOW_DAYS = 31;

    private final KopisClient client;
    private final KopisXmlParser parser;
    private final PerformanceRepository performances;
    private final VenueRepository venues;
    private final KopisCallLogRepository callLog;
    private final SyncRunRepository runs;
    private final KopisSyncLockRepository lock;
    private final KopisProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Counter callSuccess;
    private final Counter callFailure;
    private final Counter parseFailures;

    public KopisSyncService(
            KopisClient client,
            KopisXmlParser parser,
            PerformanceRepository performances,
            VenueRepository venues,
            KopisCallLogRepository callLog,
            SyncRunRepository runs,
            KopisSyncLockRepository lock,
            KopisProperties properties,
            Clock clock,
            ObjectMapper objectMapper,
            MeterRegistry registry) {
        this.client = client;
        this.parser = parser;
        this.performances = performances;
        this.venues = venues;
        this.callLog = callLog;
        this.runs = runs;
        this.lock = lock;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.callSuccess = Counter.builder(MetricNames.KOPIS_CALLS)
                .tag(MetricNames.TAG_RESULT, "success")
                .register(registry);
        this.callFailure = Counter.builder(MetricNames.KOPIS_CALLS)
                .tag(MetricNames.TAG_RESULT, "failure")
                .register(registry);
        this.parseFailures = Counter.builder(MetricNames.KOPIS_PARSE_FAILURES).register(registry);
        Gauge.builder(MetricNames.KOPIS_DETAIL_PENDING, performances, PerformanceRepository::countPending)
                .register(registry);
    }

    /** 동기화를 한 번 돌린다. 다른 인스턴스가 돌리는 중이면 아무것도 하지 않고 SKIPPED를 돌려준다. */
    public SyncRun sync(String trigger) {
        String owner = UUID.randomUUID().toString();
        Instant now = clock.instant();
        if (!lock.tryAcquire(LOCK, owner, now, now.plus(properties.lockLease()))) {
            log.info("KOPIS 동기화가 이미 실행 중이라 건너뛴다");
            return new SyncRun(0, SyncStatus.SKIPPED, null, false, 0, 0, 0, 0, 0, null);
        }
        try {
            return run(trigger, now);
        } finally {
            lock.release(LOCK, owner);
        }
    }

    private SyncRun run(String trigger, Instant startedAt) {
        LocalDate today = LocalDate.now(clock);
        LocalDate afterDate = runs.lastListCompletedDate(properties.zone()).orElse(null);
        long runId = runs.start(trigger, startedAt, afterDate);
        CallBudget budget =
                new CallBudget(callLog, clock, client.metered(), properties.syncMaxCalls(), properties.dailyLimit());
        Progress progress = new Progress();
        SyncStatus status = SyncStatus.SUCCESS;
        String error = null;
        try {
            progress.listCompleted = syncList(budget, progress, today, afterDate);
            syncDetails(budget, progress, today);
            if (!progress.listCompleted) {
                status = SyncStatus.PARTIAL;
            }
        } catch (BudgetExhausted e) {
            status = SyncStatus.PARTIAL;
        } catch (KopisCallException e) {
            status = SyncStatus.FAILED;
            error = e.getMessage();
            log.warn("KOPIS 호출 실패로 동기화를 끝낸다: {}", e.getMessage());
        } catch (KopisParseException e) {
            status = SyncStatus.FAILED;
            error = "응답 파싱 실패: " + e.getMessage();
            log.warn("KOPIS 응답을 해석하지 못해 동기화를 끝낸다: {}", e.getMessage());
        } catch (RuntimeException e) {
            status = SyncStatus.FAILED;
            error = "예상하지 못한 오류: " + e.getClass().getSimpleName();
            log.error("KOPIS 동기화 중 오류", e);
        }
        SyncRun result = new SyncRun(
                runId,
                status,
                afterDate,
                progress.listCompleted,
                budget.used(),
                progress.listed,
                progress.detailsFetched,
                progress.facilitiesFetched,
                progress.parseFailures,
                error);
        runs.finish(runId, clock.instant(), result);
        log.info(
                "KOPIS 동기화 {}: 호출 {}, 목록 {}, 상세 {}, 시설 {}, 파싱 실패 {}",
                status,
                result.calls(),
                result.listed(),
                result.detailsFetched(),
                result.facilitiesFetched(),
                result.parseFailures());
        return result;
    }

    /** 기간을 31일 창으로 나눠 목록을 받는다. 항목을 건너뛰었으면 false를 돌려 다음 실행이 같은 기준일로 다시 받게 한다. */
    private boolean syncList(CallBudget budget, Progress progress, LocalDate today, LocalDate afterDate) {
        boolean complete = true;
        LocalDate last = today.plusDays(properties.windowDays() - 1L);
        for (LocalDate from = today; !from.isAfter(last); from = from.plusDays(MAX_WINDOW_DAYS)) {
            LocalDate to =
                    from.plusDays(MAX_WINDOW_DAYS - 1L).isAfter(last) ? last : from.plusDays(MAX_WINDOW_DAYS - 1L);
            int page = 1;
            while (true) {
                int currentPage = page;
                LocalDate windowFrom = from;
                String xml = call(
                        budget,
                        () -> client.listPerformances(windowFrom, to, currentPage, properties.listRows(), afterDate));
                KopisXmlParser.ListResult result = parser.parseList(xml);
                if (result.skipped() > 0) {
                    complete = false;
                    countParseFailure(progress, result.skipped());
                }
                performances.upsertSummaries(result.items(), afterDate != null, clock.instant());
                progress.listed += result.items().size();
                if (result.items().size() + result.skipped() < properties.listRows()) {
                    break;
                }
                page++;
            }
        }
        return complete;
    }

    private void syncDetails(CallBudget budget, Progress progress, LocalDate today) {
        Set<String> triedVenues = new HashSet<>();
        // 지난 실행에서 상세는 받았지만 시설을 못 받은 공연과 오래된 시설을 먼저 채운다. 밀린 상세가 호출을 다 써도 시설이 굶지 않게 하려는 순서다
        for (String mt10id :
                venues.findNeedingFetch(clock.instant().minus(properties.facilityRefresh()), today, 1000)) {
            fetchFacility(budget, progress, mt10id, triedVenues);
        }
        List<String> failed = new ArrayList<>();
        while (true) {
            PendingPerformance next = performances
                    .nextPending(today, properties.detailMaxAttempts(), failed)
                    .orElse(null);
            if (next == null) {
                return;
            }
            String xml = call(budget, () -> client.performanceDetail(next.mt20id()));
            KopisPerformanceDetail detail;
            try {
                detail = parser.parseDetail(xml);
                if (!detail.mt20id().equals(next.mt20id())) {
                    throw new KopisParseException("요청한 공연과 응답의 공연 ID가 다르다");
                }
            } catch (KopisParseException e) {
                log.warn("공연상세 파싱 실패 {}: {}", next.mt20id(), e.getMessage());
                performances.recordDetailFailure(next.mt20id(), properties.detailMaxAttempts());
                failed.add(next.mt20id());
                countParseFailure(progress, 1);
                continue;
            }
            performances.applyDetail(detail, relatesJson(detail), clock.instant());
            progress.detailsFetched++;
            if (detail.mt10id() != null) {
                fetchFacility(budget, progress, detail.mt10id(), triedVenues);
            }
        }
    }

    private void fetchFacility(CallBudget budget, Progress progress, String mt10id, Set<String> tried) {
        Instant refreshBefore = clock.instant().minus(properties.facilityRefresh());
        if (venues.isFresh(mt10id, refreshBefore) || !tried.add(mt10id)) {
            return;
        }
        String xml = call(budget, () -> client.facilityDetail(mt10id));
        try {
            KopisFacility facility = parser.parseFacility(xml);
            venues.upsert(facility, clock.instant());
            progress.facilitiesFetched++;
        } catch (KopisParseException e) {
            log.warn("공연시설상세 파싱 실패 {}: {}", mt10id, e.getMessage());
            countParseFailure(progress, 1);
        }
    }

    /** 호출 한도를 쓰고 호출한다. 한도가 없으면 호출하지 않고 BudgetExhausted로 실행을 멈춘다. */
    private String call(CallBudget budget, Supplier<String> request) {
        if (!budget.tryAcquire()) {
            throw new BudgetExhausted();
        }
        try {
            String response = request.get();
            callSuccess.increment();
            return response;
        } catch (KopisCallException e) {
            callFailure.increment();
            throw e;
        }
    }

    private void countParseFailure(Progress progress, int count) {
        progress.parseFailures += count;
        parseFailures.increment(count);
    }

    private String relatesJson(KopisPerformanceDetail detail) {
        try {
            return objectMapper.writeValueAsString(detail.relates());
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static final class Progress {
        boolean listCompleted;
        int listed;
        int detailsFetched;
        int facilitiesFetched;
        int parseFailures;
    }

    /** 호출 상한에 닿아 실행을 멈춘다는 신호. 오류가 아니라 정상적인 중단이라 스택을 만들지 않는다. */
    private static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted() {
            super("호출 상한", null, false, false);
        }
    }
}
