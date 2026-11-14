package io.github.kanabig.curtaincall.mockpg.config;

import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MockPgConfig.Properties.class)
public class MockPgConfig {

    @ConfigurationProperties("mock-pg")
    public record Properties(long timeoutHangMs) {}

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** 확률 판정용. 테스트가 0/1 확률로 결과를 고정하므로 주입받아 쓴다. */
    @Bean
    RandomGenerator randomGenerator() {
        return new Random();
    }
}
