package io.github.kanabig.curtaincall.mockpg.service;

import io.github.kanabig.curtaincall.mockpg.dto.ApprovalResponse;

/** dropResponse가 true면 거래는 기록됐지만 컨트롤러가 응답을 보내지 않고 연결을 끊는다. */
public record ApprovalResult(ApprovalResponse response, boolean dropResponse) {}
