package io.github.kanabig.curtaincall.searchassist.client;

/** 지금까지 성공한 호출이 쓴 토큰 합계. 평가 실행기가 비용을 계산하는 데 쓰며 API 응답에는 나가지 않는다. */
public record Usage(long inputTokens, long outputTokens) {}
