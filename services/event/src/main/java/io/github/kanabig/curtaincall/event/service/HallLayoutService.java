package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository;
import io.github.kanabig.curtaincall.event.repository.HallSeatRepository.HallScale;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EVT-6. 공연장 좌석 배치 템플릿을 한 번 만들어 저장한다. 이미 있으면 다시 만들지 않으므로 호출을 몇 번 하든, 동시에 하든 배치는 처음 그대로다. 객석 수가 비었거나 0이거나
 * {@value SeatLayoutGenerator#MAX_SEATS}석을 넘는 공연장은 배치를 만들지 않고(판매할 수 없는 공연장) 그 사유를 돌려준다.
 */
@Service
public class HallLayoutService {

    private static final Logger log = LoggerFactory.getLogger(HallLayoutService.class);

    private final HallSeatRepository seats;
    private final SeatLayoutGenerator generator;
    private final Clock clock;

    public HallLayoutService(HallSeatRepository seats, SeatLayoutGenerator generator, Clock clock) {
        this.seats = seats;
        this.generator = generator;
        this.clock = clock;
    }

    @Transactional
    public Result ensureLayout(String mt13id) {
        if (seats.hasLayout(mt13id)) {
            return Result.created(Outcome.EXISTING, seats.findSeats(mt13id));
        }
        HallScale scale = seats.findScale(mt13id).orElse(null);
        if (scale == null) {
            return Result.failed(Outcome.UNKNOWN_HALL);
        }
        if (scale.seatScale() == null || scale.seatScale() < 1) {
            log.warn("공연장 객석 수가 없어 배치를 만들지 않는다 mt13id={} seatScale={}", mt13id, scale.seatScale());
            return Result.failed(Outcome.NO_SEAT_SCALE);
        }
        if (scale.seatScale() > SeatLayoutGenerator.MAX_SEATS) {
            log.warn("공연장 객석 수가 상한을 넘어 배치를 만들지 않는다 mt13id={} seatScale={}", mt13id, scale.seatScale());
            return Result.failed(Outcome.SEAT_SCALE_TOO_LARGE);
        }
        List<SeatSpec> generated = generator.generate(scale.seatScale(), scale.disabledSeatScale());
        if (!seats.insertLayout(mt13id, generated.size(), clock.instant())) {
            // 동시에 만든 쪽이 먼저 커밋했다. 그쪽 배치를 쓴다.
            return Result.created(Outcome.EXISTING, seats.findSeatsLatest(mt13id));
        }
        seats.insertSeats(mt13id, generated);
        return Result.created(Outcome.CREATED, generated);
    }

    public enum Outcome {
        CREATED,
        EXISTING,
        UNKNOWN_HALL,
        NO_SEAT_SCALE,
        SEAT_SCALE_TOO_LARGE
    }

    public record Result(Outcome outcome, List<SeatSpec> seats) {

        static Result created(Outcome outcome, List<SeatSpec> seats) {
            return new Result(outcome, seats);
        }

        static Result failed(Outcome outcome) {
            return new Result(outcome, List.of());
        }

        public boolean available() {
            return outcome == Outcome.CREATED || outcome == Outcome.EXISTING;
        }
    }
}
