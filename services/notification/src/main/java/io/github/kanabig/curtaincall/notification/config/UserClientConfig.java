package io.github.kanabig.curtaincall.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({UserClientProperties.class, NotificationProperties.class})
public class UserClientConfig {

    static final String USER_REST_CLIENT = "userRestClient";

    /** 추적 ID 전파 인터셉터는 Boot의 RestClient.Builder에 이미 붙어 있다. */
    @Bean(USER_REST_CLIENT)
    public RestClient userRestClient(RestClient.Builder builder, UserClientProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        return builder.baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
