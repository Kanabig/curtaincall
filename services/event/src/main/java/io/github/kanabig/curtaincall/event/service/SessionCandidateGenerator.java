package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.dto.SessionCandidate;
import io.github.kanabig.curtaincall.event.dto.SessionRule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EVT-8. 회차 규칙과 공연 기간으로 회차 후보를 만든다. 판매 오픈 시각은 회차 {@value #SALE_OPEN_LEAD_DAYS}일 전 14:00으로 시연용이다(같은 날 회차는 오픈 시각이 같다). 상설 공연처럼
 * 기간이 길면 from부터 maxSessions개까지만 만든다.
 */
@Component
public class SessionCandidateGenerator {

    static final int SALE_OPEN_LEAD_DAYS = 14;
    private static final LocalTime SALE_OPEN_TIME = LocalTime.of(14, 0);

    public List<SessionCandidate> generate(
            SessionRule rule, LocalDate startDate, LocalDate endDate, LocalDate from, int maxSessions) {
        List<SessionCandidate> candidates = new ArrayList<>();
        LocalDate day = startDate.isBefore(from) ? from : startDate;
        for (; !day.isAfter(endDate) && candidates.size() < maxSessions; day = day.plusDays(1)) {
            LocalDate date = day;
            LocalDateTime saleOpenAt = date.minusDays(SALE_OPEN_LEAD_DAYS).atTime(SALE_OPEN_TIME);
            rule.slots().stream()
                    .filter(slot -> slot.day() == date.getDayOfWeek())
                    .map(SessionRule.Slot::time)
                    .sorted(Comparator.naturalOrder())
                    .limit(maxSessions - candidates.size())
                    .forEach(time -> candidates.add(new SessionCandidate(date.atTime(time), saleOpenAt)));
        }
        return candidates;
    }
}
