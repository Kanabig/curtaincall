package io.github.kanabig.curtaincall.mockpg.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 승인 요청에 거는 장애 주입 설정. PUT은 전체 교체라 빠뜨린 필드는 0(꺼짐)이다.
 *
 * <p>delayMs는 모든 승인 요청에 적용한다. 나머지 세 확률은 지연 뒤에 timeout → failure 순으로 판정하고, dropResponse는 승인을 처리한 뒤에 판정한다.
 */
public record FaultSettings(
        @Min(0) @Max(60_000) long delayMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double failureRate,
        @DecimalMin("0.0") @DecimalMax("1.0") double timeoutRate,
        @DecimalMin("0.0") @DecimalMax("1.0") double dropResponseRate) {

    public static final FaultSettings NONE = new FaultSettings(0, 0, 0, 0);
}
