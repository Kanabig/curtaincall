package io.github.kanabig.curtaincall.testsupport;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 서비스 통합 테스트가 {@code @Import(TestContainersConfig.class)}로 가져다 쓴다.
 *
 * <p>{@code @ServiceConnection}이 datasource, Kafka, Redis 접속 정보를 Spring 설정에 연결한다. 컨테이너는 {@link Containers}가
 * 공유하므로 {@code destroyMethod = ""}로 컨텍스트가 닫힐 때 멈추지 않게 한다(기본값은 close()를 호출해 컨테이너를 멈춘다).
 *
 * <p>서비스가 쓰지 않는 의존성(Kafka 클라이언트 등)이 클래스패스에 없으면 해당 접속 정보는 만들어지지 않고 컨테이너만 떠 있는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

    @Bean(destroyMethod = "")
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return Containers.MYSQL;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    KafkaContainer kafka() {
        return Containers.KAFKA;
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    RedisContainer redis() {
        return Containers.REDIS;
    }
}
