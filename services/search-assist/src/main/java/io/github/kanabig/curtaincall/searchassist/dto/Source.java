package io.github.kanabig.curtaincall.searchassist.dto;

/** 조건을 만든 경로. FALLBACK은 keyword만 채운 결과다. */
public enum Source {
    LLM,
    MOCK,
    FALLBACK
}
