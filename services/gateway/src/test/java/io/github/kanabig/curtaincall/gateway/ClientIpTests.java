package io.github.kanabig.curtaincall.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.gateway.filter.ClientIp;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpTests {

    private static MockServerHttpRequest.BaseBuilder<?> request() throws Exception {
        return MockServerHttpRequest.get("/")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 5000));
    }

    @Test
    @DisplayName("기본은 연결의 원격 주소이고 X-Forwarded-For는 무시한다")
    void remoteAddressByDefault() throws Exception {
        var req = request().header("X-Forwarded-For", "1.1.1.1").build();
        assertThat(ClientIp.resolve(req, false)).isEqualTo("192.0.2.10");
    }

    @Test
    @DisplayName("프록시를 신뢰하면 X-Forwarded-For의 마지막 값을 쓴다")
    void lastForwardedValue() throws Exception {
        var req = request().header("X-Forwarded-For", "6.6.6.6, 203.0.113.5").build();
        assertThat(ClientIp.resolve(req, true)).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("헤더가 없거나 비어 있으면 원격 주소로 돌아간다")
    void fallsBack() throws Exception {
        assertThat(ClientIp.resolve(request().build(), true)).isEqualTo("192.0.2.10");
        assertThat(ClientIp.resolve(request().header("X-Forwarded-For", " ").build(), true))
                .isEqualTo("192.0.2.10");
    }
}
