package io.github.kanabig.curtaincall.event.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpKopisClientTests {

    private static final String KEY = "secret-test-key";
    private static FakeKopisServer server;
    private HttpKopisClient client;

    @BeforeAll
    static void startServer() {
        server = new FakeKopisServer();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.reset();
        client = new HttpKopisClient(properties(Duration.ofSeconds(2)));
    }

    @Test
    @DisplayName("목록 요청은 기간, 페이지, 건수, 서비스 키를 쿼리로 보내고 afterdate는 있을 때만 붙인다")
    void sendsListQuery() {
        client.listPerformances(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 12, 14), 2, 100, null);
        client.listPerformances(
                LocalDate.of(2026, 11, 14), LocalDate.of(2026, 12, 14), 1, 100, LocalDate.of(2026, 11, 13));

        URI plain = server.requests().get(0);
        URI incremental = server.requests().get(1);
        assertThat(plain.getPath()).isEqualTo("/pblprfr");
        assertThat(plain.getQuery())
                .contains("service=" + KEY, "stdate=20261114", "eddate=20261214", "cpage=2", "rows=100")
                .doesNotContain("afterdate");
        assertThat(incremental.getQuery()).contains("afterdate=20261113");
    }

    @Test
    @DisplayName("상세와 시설상세는 ID를 경로에 넣는다")
    void sendsDetailPath() {
        String detail = client.performanceDetail("PF305735");
        String facility = client.facilityDetail("FC001535");

        assertThat(server.requests()).extracting(URI::getPath).containsExactly("/pblprfr/PF305735", "/prfplc/FC001535");
        assertThat(detail).contains("PF305735");
        assertThat(facility).contains("FC001535");
    }

    @Test
    @DisplayName("5xx는 호출 예외이고 메시지에 서비스 키가 없다")
    void serverErrorHidesKey() {
        server.status = 503;

        assertThatThrownBy(() -> client.performanceDetail("PF305735"))
                .isInstanceOf(KopisCallException.class)
                .hasMessageContaining("503")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY))
                .hasNoCause();
    }

    @Test
    @DisplayName("read timeout은 호출 예외이고 메시지에 서비스 키가 없다")
    void timeoutHidesKey() {
        server.delayMs = 1000;
        HttpKopisClient slow = new HttpKopisClient(properties(Duration.ofMillis(200)));

        assertThatThrownBy(() -> slow.performanceDetail("PF305735"))
                .isInstanceOf(KopisCallException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY))
                .hasNoCause();
    }

    @Test
    @DisplayName("키 없이 live 모드로 만들면 기동 단계에서 실패한다")
    void liveWithoutKeyFails() {
        KopisProperties blank = new KopisProperties(
                KopisProperties.Mode.LIVE,
                "",
                server.baseUrl(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                100,
                60,
                90,
                100,
                Duration.ofDays(30),
                3,
                java.time.ZoneId.of("Asia/Seoul"),
                Duration.ofMinutes(30));

        assertThatThrownBy(() -> new HttpKopisClient(blank)).isInstanceOf(IllegalStateException.class);
    }

    private static KopisProperties properties(Duration readTimeout) {
        return new KopisProperties(
                KopisProperties.Mode.LIVE,
                KEY,
                server.baseUrl(),
                Duration.ofSeconds(1),
                readTimeout,
                100,
                60,
                90,
                100,
                Duration.ofDays(30),
                3,
                java.time.ZoneId.of("Asia/Seoul"),
                Duration.ofMinutes(30));
    }
}
