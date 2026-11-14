package io.github.kanabig.curtaincall.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("curtaincall.inventory")
public record SeatInventoryProperties(@DefaultValue("1000") int insertBatchSize) {}
