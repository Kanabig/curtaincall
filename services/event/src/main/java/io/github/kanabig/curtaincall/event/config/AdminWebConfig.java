package io.github.kanabig.curtaincall.event.config;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 관리자 경로 방어선. gateway가 ADMIN만 통과시키지만, 서비스에 직접 닿는 요청도 같은 기준으로 막는다.
 * gateway가 검증해 넣는 {@code X-User-Role}만 믿는다.
 */
@Configuration(proxyBeanMethods = false)
public class AdminWebConfig implements WebMvcConfigurer {

    static final String ROLE_HEADER = "X-User-Role";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if (!"ADMIN".equals(request.getHeader(ROLE_HEADER))) {
                            throw new BusinessException(EventErrorCode.FORBIDDEN);
                        }
                        return true;
                    }
                })
                .addPathPatterns("/api/v1/admin/**");
    }
}
