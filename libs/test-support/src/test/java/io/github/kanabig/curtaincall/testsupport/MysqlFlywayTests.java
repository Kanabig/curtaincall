package io.github.kanabig.curtaincall.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestContainersConfig.class)
class MysqlFlywayTests {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway가 실제 MySQL 8.4에 마이그레이션을 적용하고 조건부 UPDATE가 동작한다")
    void migrationAppliedAndConditionalUpdateWorks() {
        // given: V1 마이그레이션이 넣은 두 행
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sample_seat", Integer.class);
        assertThat(count).isEqualTo(2);

        // when: 점유 가능한 좌석만 갱신하는 조건부 UPDATE를 두 번 실행한다
        int first = jdbcTemplate.update(
                "UPDATE sample_seat SET status = 'HELD' WHERE label = 'A-1' AND status = 'AVAILABLE'");
        int second = jdbcTemplate.update(
                "UPDATE sample_seat SET status = 'HELD' WHERE label = 'A-1' AND status = 'AVAILABLE'");

        // then: 두 번째는 영향 행이 0이다
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        assertThat(version).startsWith("8.4");
    }
}
