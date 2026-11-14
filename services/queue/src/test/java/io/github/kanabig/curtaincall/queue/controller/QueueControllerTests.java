package io.github.kanabig.curtaincall.queue.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QueueControllerTests extends QueueIntegrationTest {

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    QueueRepository repository;

    private String entries() {
        return "/api/v1/queue/sessions/" + sessionId + "/entries";
    }

    @Test
    @DisplayName("[QUE-1] 설정이 없는 회차는 404")
    void unknownSession() throws Exception {
        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("[QUE-1] 오픈 전에는 409 SALE_NOT_OPENED와 오픈 시각을 돌려주고 대기열에 넣지 않는다")
    void beforeOpen() throws Exception {
        putConf(60_000, 50);

        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_OPENED"))
                .andExpect(jsonPath("$.opensAt").isString());

        org.assertj.core.api.Assertions.assertThat(redis.opsForZSet().zCard("q:" + sessionId + ":waiting"))
                .isZero();
    }

    @Test
    @DisplayName("[QUE-6] 대기 포기는 204로 대기열에서 빼고, 다시 해도 204다")
    void leave() throws Exception {
        putConf(-1000, 50);
        mvc.perform(post(entries()).header("X-User-Id", 1)).andExpect(status().isCreated());

        mvc.perform(delete(entries() + "/me").header("X-User-Id", 1)).andExpect(status().isNoContent());
        mvc.perform(delete(entries() + "/me").header("X-User-Id", 1)).andExpect(status().isNoContent());
        mvc.perform(get(entries() + "/me").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("[QUE-2] 처음 진입하면 201과 순번, 다시 진입하면 200과 같은 순번이고 뒤에 온 사람은 그 뒤 순번이다")
    void enterKeepsPosition() throws Exception {
        putConf(-1000, 50);

        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.ahead").value(0));
        mvc.perform(post(entries()).header("X-User-Id", 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(2));
        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    @DisplayName("[QUE-3] 상태 조회는 순번, 앞 사람 수, 예상 대기 시간, 폴링 간격을 준다")
    void pollStatus() throws Exception {
        putConf(-1000, 1);
        for (int user = 1; user <= 12; user++) {
            mvc.perform(post(entries()).header("X-User-Id", user)).andExpect(status().isCreated());
        }

        mvc.perform(get(entries() + "/me").header("X-User-Id", 12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(12))
                .andExpect(jsonPath("$.ahead").value(11))
                .andExpect(jsonPath("$.etaSeconds").value(11))
                // 앞 사람 11명 > 초당 1명 x 10초 이므로 3초 구간(2400~3600ms)
                .andExpect(jsonPath("$.pollAfterMs")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.greaterThanOrEqualTo(2400),
                                org.hamcrest.Matchers.lessThanOrEqualTo(3600))));
    }

    @Test
    @DisplayName("[QUE-3] 상태 조회가 heartbeat로 seen을 갱신하고, 대기열에 없는 사용자는 EXPIRED다")
    void heartbeatAndExpired() throws Exception {
        putConf(-1000, 50);
        mvc.perform(post(entries()).header("X-User-Id", 1)).andExpect(status().isCreated());
        String seenKey = "q:" + sessionId + ":seen";
        redis.opsForZSet().add(seenKey, "1", 1);

        mvc.perform(get(entries() + "/me").header("X-User-Id", 1)).andExpect(status().isOk());
        Double seen = redis.opsForZSet().score(seenKey, "1");
        org.assertj.core.api.Assertions.assertThat(seen).isGreaterThan(1000);

        mvc.perform(get(entries() + "/me").header("X-User-Id", 99))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    @DisplayName("판매 중지된 회차는 진입과 조회 모두 409 SESSION_NOT_ON_SALE")
    void suspended() throws Exception {
        putConf(-1000, 50);
        redis.opsForHash().put("q:" + sessionId + ":conf", "status", "SUSPENDED");

        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_ON_SALE"));
        mvc.perform(get(entries() + "/me").header("X-User-Id", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_ON_SALE"));
    }

    @Test
    @DisplayName("[NFR-11] Redis 호출이 실패하면 진입과 조회는 503 QUEUE_UNAVAILABLE")
    void redisDown() throws Exception {
        doThrow(new RedisConnectionFailureException("down")).when(repository).enter(anyLong(), anyLong());
        doThrow(new RedisConnectionFailureException("down")).when(repository).status(anyLong(), anyLong());

        mvc.perform(post(entries()).header("X-User-Id", 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUEUE_UNAVAILABLE"));
        mvc.perform(get(entries() + "/me").header("X-User-Id", 1)).andExpect(status().isServiceUnavailable());
    }
}
