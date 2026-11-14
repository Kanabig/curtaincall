package io.github.kanabig.curtaincall.event.client;

import io.github.kanabig.curtaincall.event.config.KopisModes;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

/**
 * 실제 KOPIS 호출. 서비스 키가 쿼리스트링에 실리므로 예외와 로그에 URL이 나가지 않게 한다. 재시도는 하지 않는다. 재시도도 하루 한도를 쓰기 때문이다.
 */
@Component
@ConditionalOnExpression(KopisModes.LIVE)
public class HttpKopisClient implements KopisClient {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final String apiKey;

    public HttpKopisClient(KopisProperties properties) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException("curtaincall.kopis.mode=live에는 KOPIS_API_KEY가 필요하다");
        }
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        // 외부 서비스에 내부 추적 ID 헤더를 보내지 않으려고 Boot가 만든 빌더 대신 새 빌더를 쓴다
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
        this.apiKey = properties.apiKey();
    }

    @Override
    public boolean metered() {
        return true;
    }

    @Override
    public String listPerformances(LocalDate from, LocalDate to, int page, int rows, LocalDate afterDate) {
        return get(uri -> {
            uri.path("/pblprfr")
                    .queryParam("service", apiKey)
                    .queryParam("stdate", from.format(YYYYMMDD))
                    .queryParam("eddate", to.format(YYYYMMDD))
                    .queryParam("cpage", page)
                    .queryParam("rows", rows);
            if (afterDate != null) {
                uri.queryParam("afterdate", afterDate.format(YYYYMMDD));
            }
            return uri.build();
        });
    }

    @Override
    public String performanceDetail(String mt20id) {
        return get(
                uri -> uri.path("/pblprfr/{id}").queryParam("service", apiKey).build(mt20id));
    }

    @Override
    public String facilityDetail(String mt10id) {
        return get(uri -> uri.path("/prfplc/{id}").queryParam("service", apiKey).build(mt10id));
    }

    private String get(Function<UriBuilder, java.net.URI> uri) {
        try {
            byte[] body = restClient.get().uri(uri).retrieve().body(byte[].class);
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        } catch (RestClientResponseException e) {
            throw new KopisCallException("KOPIS HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new KopisCallException("KOPIS 연결 실패: " + cause.getClass().getSimpleName());
        }
    }
}
