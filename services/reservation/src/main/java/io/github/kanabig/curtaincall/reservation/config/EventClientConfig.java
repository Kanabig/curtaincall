package io.github.kanabig.curtaincall.reservation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({EventClientProperties.class, SeatInventoryProperties.class})
public class EventClientConfig {

    static final String EVENT_REST_CLIENT = "eventRestClient";

    /** 추적 ID 전파 인터셉터는 Boot의 RestClient.Builder에 이미 붙어 있다. */
    @Bean(EVENT_REST_CLIENT)
    public RestClient eventRestClient(RestClient.Builder builder, EventClientProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
