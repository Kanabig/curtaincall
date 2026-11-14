package io.github.kanabig.curtaincall.common.web.config;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RequestIdPropagationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(RestClientAutoConfiguration.class, CommonWebAutoConfiguration.class));

    @Test
    @DisplayName("MDC의 추적 ID가 RestClient 호출의 X-Request-Id 헤더로 나간다")
    void restClientCarriesRequestId() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://downstream/ping"))
                    .andExpect(header("X-Request-Id", "req-42"))
                    .andRespond(withSuccess());

            try (RequestIdContext.Scope ignored = RequestIdContext.open("req-42")) {
                builder.build().get().uri("http://downstream/ping").retrieve().toBodilessEntity();
            }

            server.verify();
        });
    }

    @Test
    @DisplayName("추적 ID가 없으면 헤더를 붙이지 않는다")
    void noHeaderWithoutRequestId() {
        runner.run(context -> {
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://downstream/ping"))
                    .andExpect(request -> org.assertj.core.api.Assertions.assertThat(
                                    request.getHeaders().containsKey("X-Request-Id"))
                            .isFalse())
                    .andRespond(withSuccess());

            builder.build().get().uri("http://downstream/ping").retrieve().toBodilessEntity();

            server.verify();
        });
    }
}
