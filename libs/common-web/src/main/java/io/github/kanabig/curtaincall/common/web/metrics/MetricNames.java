package io.github.kanabig.curtaincall.common.web.metrics;

/**
 * 도메인 지표의 이름과 태그. 계측은 각 기능에서 하고, 이름만 여기서 정해 대시보드와 측정 스크립트가 같은 이름을 쓴다.
 *
 * <p>Micrometer 이름은 점 표기이고 Prometheus에서는 밑줄로 바뀐다(예: {@code seat.hold} 타이머 → {@code
 * seat_hold_seconds_*}). 모든 지표에는 {@code application} 태그(서비스 이름)가 공통으로 붙는다. 태그 값은 종류가 적은 것만 쓴다(사용자 ID, 주문 ID
 * 금지).
 */
public final class MetricNames {

    /** 좌석 점유. Timer. 태그 result = success, conflict, limit_exceeded, error. 지연 분포와 성공·실패 수를 함께 본다. */
    public static final String SEAT_HOLD = "seat.hold";

    /** 재고 생성을 기다리는(PENDING) 회차 수. Gauge. reservation이 등록한다. */
    public static final String SEAT_STOCK_PENDING = "seat.stock.pending";

    /** 가장 오래된 PENDING 회차의 대기 시간(초). Gauge. PENDING이 없으면 0. reservation이 등록한다. */
    public static final String SEAT_STOCK_PENDING_OLDEST_AGE = "seat.stock.pending.oldest.age";

    /** 대기열 길이. Gauge. 태그 schedule(회차). */
    public static final String QUEUE_LENGTH = "queue.length";

    /** 입장 토큰을 가진 활성 사용자 수. Gauge. 태그 schedule(회차). */
    public static final String QUEUE_ACTIVE_USERS = "queue.active.users";

    /** 아직 발행하지 못한 Outbox 행 수. Gauge. outbox 모듈이 등록한다. */
    public static final String OUTBOX_UNPUBLISHED = "outbox.unpublished";

    /** 릴레이가 발행한 건수. Counter. outbox 모듈이 등록한다. */
    public static final String OUTBOX_RELAY_PUBLISHED = "outbox.relay.published";

    /** 릴레이가 발행에 실패한 건수. Counter. outbox 모듈이 등록한다. */
    public static final String OUTBOX_RELAY_FAILED = "outbox.relay.failed";

    /** 컨슈머가 멱등 처리로 건너뛴 중복 메시지 수. Counter. outbox 모듈이 등록한다. */
    public static final String OUTBOX_CONSUMER_DUPLICATES = "outbox.consumer.duplicates";

    /** DLT로 보낸 메시지 수. Counter(태그 없음). outbox 모듈이 등록한다. */
    public static final String OUTBOX_CONSUMER_DLT = "outbox.consumer.dlt";

    /** DLT에서 원래 토픽으로 다시 넣은 메시지 수. Counter. 태그 topic(원래 토픽). outbox 모듈이 등록한다. */
    public static final String OUTBOX_DLT_REPLAYED = "outbox.dlt.replayed";

    /** 상태별 주문 수. Gauge. 태그 state(주문 상태 = Saga 상태). */
    public static final String ORDER_COUNT = "order.count";

    /** 결제 결과 중 주의가 필요한 것의 발생 수. Counter. 태그 status = UNKNOWN, CANCEL_FAILED. */
    public static final String PAYMENT_ATTENTION = "payment.attention";

    /** 결제 응답을 기다리다 재전송 한도를 넘긴 주문의 발생 수. Counter(태그 없음). reservation이 등록한다. */
    public static final String ORDER_PAYMENT_STALLED = "order.payment.stalled";

    /** 실패·만료로 끝난 주문에 승인 응답이 온 횟수. Counter(태그 없음). 0이 아니면 수동 확인 대상. reservation이 등록한다. */
    public static final String ORDER_APPROVED_AFTER_TERMINAL = "order.payment.approved_after_terminal";

    /** 결과를 모른 채 남아 있는 결제 수(현재 UNKNOWN 상태인 건). Gauge. payment가 등록한다. */
    public static final String PAYMENT_UNKNOWN = "payment.unknown";

    /** Bulkhead가 거절한 PG 호출 수. Counter. payment가 등록한다. */
    public static final String PAYMENT_PG_BULKHEAD_REJECTED = "payment.pg.bulkhead.rejected";

    public static final String PAYMENT_PG_BULKHEAD_WAIT = "payment.pg.bulkhead.wait";

    /**
     * LLM 호출 수. Counter. 태그 result = success, timeout, error, rejected(CircuitBreaker), quota_exceeded, invalid(검증 실패). search-assist가
     * 등록한다.
     */
    public static final String SEARCH_ASSIST_LLM_CALLS = "search.assist.llm.calls";

    /** LLM 호출 지연. Timer(히스토그램). search-assist가 등록한다. */
    public static final String SEARCH_ASSIST_LLM_LATENCY = "search.assist.llm.latency";

    /** 검증에 실패한 조건 수. Counter. 태그 field = region, genre, dateFrom, dateTo, maxPrice, keyword. search-assist가 등록한다. */
    public static final String SEARCH_ASSIST_VALIDATION_FAILURES = "search.assist.validation.failures";

    /** 이미 회전된 refresh token이 다시 들어와 토큰 계열을 폐기한 횟수. Counter(태그 없음). user가 등록한다. */
    public static final String USER_REFRESH_REUSE_DETECTED = "user.refresh.reuse.detected";

    /** 회전 직후 유예 시간 안에 같은 토큰이 다시 와서 계열을 두고 그 요청만 거절한 횟수. Counter(태그 없음). user가 등록한다. */
    public static final String USER_REFRESH_REUSE_GRACE = "user.refresh.reuse.grace";

    /** KOPIS 호출 수. Counter. 태그 result = success, failure. event가 등록한다. 하루 한도(100)와 비교한다. */
    public static final String KOPIS_CALLS = "kopis.calls";

    /** 응답 파싱에 실패한 건수(목록 항목, 상세, 시설상세). Counter(태그 없음). event가 등록한다. */
    public static final String KOPIS_PARSE_FAILURES = "kopis.parse.failures";

    /** 상세 조회를 기다리는 공연 수. Gauge. 호출 상한 때문에 밀린 양을 본다. event가 등록한다. */
    public static final String KOPIS_DETAIL_PENDING = "kopis.detail.pending";

    public static final String TAG_FIELD = "field";
    public static final String TAG_RESULT = "result";
    public static final String TAG_SCHEDULE = "schedule";
    public static final String TAG_STATE = "state";
    public static final String TAG_STATUS = "status";

    private MetricNames() {}
}
