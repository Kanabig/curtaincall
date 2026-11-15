package io.github.kanabig.curtaincall.event.client;

import java.time.LocalDate;

/** KOPIS 응답 XML을 문자열로 가져온다. 파싱은 {@link KopisXmlParser}가 한다. */
public interface KopisClient {

    /** 호출이 KOPIS 하루 한도에 잡히는지. fixture는 외부 호출이 아니므로 false. */
    boolean metered();

    /** 공연목록. 공연 기간이 [from, to]와 겹치는 공연. afterDate가 있으면 그 날짜 이후 등록·수정된 것만. */
    String listPerformances(LocalDate from, LocalDate to, int page, int rows, LocalDate afterDate);

    String performanceDetail(String mt20id);

    String facilityDetail(String mt10id);
}
