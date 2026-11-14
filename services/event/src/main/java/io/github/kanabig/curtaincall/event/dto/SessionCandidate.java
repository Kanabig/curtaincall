package io.github.kanabig.curtaincall.event.dto;

import java.time.LocalDateTime;

/** 회차 후보. 관리자가 확인해 등록하는 값이고, 판매 오픈 시각은 시연용 규칙으로 정한 값이다. */
public record SessionCandidate(LocalDateTime startAt, LocalDateTime saleOpenAt) {}
