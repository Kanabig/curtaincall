package io.github.kanabig.curtaincall.event.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * KOPIS 연동 설정. 하루 호출 한도(dailyLimit)는 KOPIS가 정한 값이고, 한 번의 동기화가 쓰는 상한(syncMaxCalls)은 나머지를 수동 동기화와 개발용으로 남기려고
 * 따로 둔다.
 */
@ConfigurationProperties("curtaincall.kopis")
public record KopisProperties(
        @DefaultValue("auto") Mode mode,
        @DefaultValue("") String apiKey,

        @DefaultValue("http://www.kopis.or.kr/openApi/restful")
        String baseUrl,

        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("100") int dailyLimit,
        @DefaultValue("60") int syncMaxCalls,
        @DefaultValue("90") int windowDays,
        @DefaultValue("100") int listRows,
        @DefaultValue("30d") Duration facilityRefresh,
        @DefaultValue("3") int detailMaxAttempts,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("30m") Duration lockLease) {

    public enum Mode {
        AUTO,
        LIVE,
        FIXTURE
    }
}
