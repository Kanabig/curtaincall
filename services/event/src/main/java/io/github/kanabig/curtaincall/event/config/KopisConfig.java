package io.github.kanabig.curtaincall.event.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(KopisProperties.class)
public class KopisConfig {

    /** 호출 장부의 날짜와 조회 기간이 같은 시간대(KST)를 쓰도록 시계에 시간대를 묶는다. */
    @Bean
    public Clock kopisClock(KopisProperties properties) {
        return Clock.system(properties.zone());
    }
}
