package io.github.kanabig.curtaincall.queue.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 응답 시각(입장 만료 시각, 오픈 시각)을 표기할 시간대. */
@ConfigurationProperties("curtaincall.queue")
public record QueueProperties(@DefaultValue("Asia/Seoul") ZoneId zone) {}
