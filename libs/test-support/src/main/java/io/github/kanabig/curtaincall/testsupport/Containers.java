package io.github.kanabig.curtaincall.testsupport;

import com.redis.testcontainers.RedisContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 테스트 JVM 하나에서 컨테이너를 한 번만 띄운다.
 *
 * <p>컨테이너는 클래스 로딩 때 시작하고 직접 멈추지 않는다. JVM이 끝나면 Testcontainers의 Ryuk이 정리한다. 테스트 클래스·Spring
 * 컨텍스트마다 다시 띄우면 CI 시간이 컨테이너 기동에 쓰이기 때문이다. 대신 컨텍스트가 여러 개여도 같은 컨테이너를 공유하므로 테스트끼리 데이터가 겹치지
 * 않게 키·테이블을 나눠야 한다.
 *
 * <p>이미지 태그는 compose.yml과 같은 값을 쓴다.
 */
public final class Containers {

    static final String MYSQL_IMAGE = "mysql:8.4.13";
    static final String KAFKA_IMAGE = "apache/kafka:4.3.2";
    static final String REDIS_IMAGE = "redis:8.0.6";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE);
    static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);
    static final RedisContainer REDIS = new RedisContainer(REDIS_IMAGE);

    static {
        MYSQL.start();
        KAFKA.start();
        REDIS.start();
    }

    private Containers() {}
}
