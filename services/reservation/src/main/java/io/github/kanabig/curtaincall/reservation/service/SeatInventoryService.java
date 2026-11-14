package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.client.EventClient;
import io.github.kanabig.curtaincall.reservation.client.SeatMapEntry;
import io.github.kanabig.curtaincall.reservation.config.SeatInventoryProperties;
import io.github.kanabig.curtaincall.reservation.entity.NewSeat;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** [EVT-9] 회차별 좌석 재고. 원본은 reservation이다. */
@Service
public class SeatInventoryService {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryService.class);

    private final EventClient eventClient;
    private final SeatInventoryRepository repository;
    private final SeatInventoryProperties properties;

    public SeatInventoryService(
            EventClient eventClient, SeatInventoryRepository repository, SeatInventoryProperties properties) {
        this.eventClient = eventClient;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * event에서 배치를 받아 재고를 만들고 회차를 준비 완료로 표시한다. 호출하는 쪽 트랜잭션 안에서 실행해야 한다(재고 행과 준비 표시가 함께 커밋된다). 배치를 못
     * 받으면 예외가 나고 아무것도 남지 않으므로 재시도하면 처음부터 다시 만든다.
     */
    public void createInventory(long sessionId) {
        List<SeatMapEntry> seatMap = eventClient.fetchSeatMap(sessionId);
        if (seatMap.isEmpty()) {
            throw new IllegalStateException("좌석 배치가 비어 있다: sessionId=" + sessionId);
        }
        List<NewSeat> seats = seatMap.stream()
                .map(entry -> new NewSeat(entry.seatId(), entry.grade(), entry.price()))
                .toList();
        int inserted = repository.insertAll(sessionId, seats, properties.insertBatchSize());
        repository.markReady(sessionId, seats.size());
        log.info("회차 재고를 만들었다: sessionId={} seats={} inserted={}", sessionId, seats.size(), inserted);
    }

    /** 재고가 준비되지 않은 회차는 점유를 받지 않는다. 점유 API가 맨 앞에서 부른다. */
    public void requireReady(long sessionId) {
        if (!repository.isReady(sessionId)) {
            throw new BusinessException(ReservationErrorCode.SESSION_NOT_ON_SALE, "회차 재고가 아직 준비되지 않았다");
        }
    }
}
