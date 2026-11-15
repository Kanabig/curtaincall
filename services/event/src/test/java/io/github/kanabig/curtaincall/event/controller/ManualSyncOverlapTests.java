package io.github.kanabig.curtaincall.event.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.service.KopisSyncService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** ADM-4: 수동 동기화가 돌고 있을 때 다시 요청하면 바로 409이고, 큐에 남아 나중에 한 번 더 돌지 않는다. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.scheduler.enabled=false",
            "curtaincall.kopis.bootstrap=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class ManualSyncOverlapTests extends EventIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    KopisSyncService sync;

    private ResponseEntity<String> post() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Role", "ADMIN");
        return rest.exchange("/api/v1/admin/kopis/sync", HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("실행 중 재요청은 즉시 409 KOPIS_SYNC_IN_PROGRESS이고 동기화는 한 번만 시작된다")
    void secondRequestRejectedWithoutQueueing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
                    inv.<LongConsumer>getArgument(1).accept(7L);
                    release.await(10, TimeUnit.SECONDS);
                    return null;
                })
                .when(sync)
                .sync(eq("MANUAL"), any());

        ResponseEntity<String> first = post();
        long begin = System.nanoTime();
        ResponseEntity<String> second = post();

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).contains("\"runId\":7");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("KOPIS_SYNC_IN_PROGRESS");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin)).isLessThan(2);

        release.countDown();
        Thread.sleep(300);
        verify(sync, times(1)).sync(eq("MANUAL"), any());
    }
}
