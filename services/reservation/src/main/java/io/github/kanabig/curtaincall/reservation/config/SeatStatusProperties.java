package io.github.kanabig.curtaincall.reservation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 좌석 상태 조회 캐시. {@code cacheTtl}이 0이면 캐시 없이 매번 DB를 읽는다. */
@ConfigurationProperties("curtaincall.seat-status")
public record SeatStatusProperties(@DefaultValue("2s") Duration cacheTtl) {}
