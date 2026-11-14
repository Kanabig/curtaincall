package io.github.kanabig.curtaincall.reservation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** event 내부 API 호출 설정. timeout 없이 부르면 event 장애가 소비 스레드를 붙잡는다. */
@ConfigurationProperties("curtaincall.event")
public record EventClientProperties(
        @DefaultValue("http://localhost:8082") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {}
