package io.github.kanabig.curtaincall.gateway.filter;

import java.net.InetSocketAddress;
import org.springframework.http.server.reactive.ServerHttpRequest;

/** 속도 제한에 쓰는 클라이언트 IP. */
public final class ClientIp {

    private ClientIp() {}

    /**
     * 기본은 TCP 연결의 원격 주소다. X-Forwarded-For는 클라이언트가 임의로 보낼 수 있어 신뢰하는 프록시 뒤일 때만 쓴다. 그때도 프록시가 덧붙인 마지막 값을
     * 쓴다(앞쪽 값은 클라이언트가 채운 것일 수 있다).
     */
    public static String resolve(ServerHttpRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String forwarded = String.join(",", request.getHeaders().getOrEmpty("X-Forwarded-For"));
            if (!forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? null
                : remote.getAddress().getHostAddress();
    }
}
