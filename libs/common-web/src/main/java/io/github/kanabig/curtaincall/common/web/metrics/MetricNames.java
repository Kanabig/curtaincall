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

    /** 대기열 길이. Gauge. 태그 schedule(회차). */
    public static final String QUEUE_LENGTH = "queue.length";

    /** 입장 토큰을 가진 활성 사용자 수. Gauge. 태그 schedule(회차). */
    public static final String QUEUE_ACTIVE_USERS = "queue.active.users";

    /** 아직 발행하지 못한 Outbox 행 수. Gauge. */
    public static final String OUTBOX_PENDING = "outbox.pending";

    /** DLT로 보낸 메시지 수. Counter. 태그 topic(원래 토픽). */
    public static final String MESSAGING_DLT = "messaging.dlt";

    /** 상태별 주문 수. Gauge. 태그 state(주문 상태 = Saga 상태). */
    public static final String ORDER_COUNT = "order.count";

    /** 결제 결과 중 주의가 필요한 것의 발생 수. Counter. 태그 status = UNKNOWN, CANCEL_FAILED. */
    public static final String PAYMENT_ATTENTION = "payment.attention";

    public static final String TAG_RESULT = "result";
    public static final String TAG_SCHEDULE = "schedule";
    public static final String TAG_TOPIC = "topic";
    public static final String TAG_STATE = "state";
    public static final String TAG_STATUS = "status";

    private MetricNames() {}
}
