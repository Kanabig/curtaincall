package io.github.kanabig.curtaincall.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("curtaincall.notification")
public record NotificationProperties(
        @DefaultValue("no-reply@curtaincall.local") String from) {}
