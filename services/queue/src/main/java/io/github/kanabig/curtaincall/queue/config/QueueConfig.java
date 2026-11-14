package io.github.kanabig.curtaincall.queue.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({QueueProperties.class, JwtProperties.class})
public class QueueConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultRedisScript<java.util.List> enterScript() {
        return script("redis/enter.lua");
    }

    @Bean
    DefaultRedisScript<java.util.List> statusScript() {
        return script("redis/status.lua");
    }

    @Bean
    DefaultRedisScript<Long> applyConfScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/apply-conf.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    DefaultRedisScript<java.util.List> admitScript() {
        return script("redis/admit.lua");
    }

    @Bean
    DefaultRedisScript<Long> removeStaleScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/remove-stale.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static DefaultRedisScript<java.util.List> script(String path) {
        DefaultRedisScript<java.util.List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(java.util.List.class);
        return script;
    }
}
