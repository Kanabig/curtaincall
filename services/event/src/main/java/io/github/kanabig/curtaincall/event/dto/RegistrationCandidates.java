package io.github.kanabig.curtaincall.event.dto;

import java.time.LocalDate;
import java.util.List;

/** [ADM-1] 등록 후보 조회 응답. */
public final class RegistrationCandidates {

    private RegistrationCandidates() {}

    /**
     * registered는 이미 판매 대상으로 등록됐다는 뜻이고 performanceId가 그때의 ID다. registrable은 등록할 수 있는 공연인지 판정한 결과이며, 아니면 reason에 등록 API가 409로 돌려주는 것과 같은
     * 코드(PERFORMANCE_NOT_SYNCED, HALL_UNKNOWN, HALL_NO_SEAT_SCALE, HALL_SEAT_SCALE_TOO_LARGE)가 들어간다.
     */
    public record Candidate(
            String kopisId,
            String name,
            String facilityName,
            String genre,
            PerformanceState state,
            LocalDate startDate,
            LocalDate endDate,
            boolean registered,
            Long performanceId,
            boolean registrable,
            String reason) {}

    public record CandidatePage(List<Candidate> content, int page, int size, long totalElements) {}
}
