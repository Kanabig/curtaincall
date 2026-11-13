package io.github.kanabig.curtaincall.common.web.config;

import io.github.kanabig.curtaincall.common.web.error.GlobalExceptionHandler;
import io.github.kanabig.curtaincall.common.web.trace.RequestIdClientInterceptor;
import io.github.kanabig.curtaincall.common.web.trace.RequestIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Boot가 만드는 RestClient.Builder로 만든 클라이언트가 추적 ID를 전파한다. */
    @Bean
    public RestClientCustomizer requestIdRestClientCustomizer() {
        return builder -> builder.requestInterceptor(new RequestIdClientInterceptor());
    }

    /** RestTemplateBuilder로 만든 RestTemplate도 같다. */
    @Bean
    public RestTemplateCustomizer requestIdRestTemplateCustomizer() {
        return restTemplate -> restTemplate.getInterceptors().add(new RequestIdClientInterceptor());
    }
}
