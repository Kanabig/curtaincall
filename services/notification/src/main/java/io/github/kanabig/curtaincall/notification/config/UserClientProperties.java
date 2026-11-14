package io.github.kanabig.curtaincall.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** user 내부 API 호출 설정. timeout 없이 부르면 user 장애가 소비 스레드를 붙잡는다. */
@ConfigurationProperties("curtaincall.user")
public record UserClientProperties(
        @DefaultValue("http://localhost:8081") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout) {}
