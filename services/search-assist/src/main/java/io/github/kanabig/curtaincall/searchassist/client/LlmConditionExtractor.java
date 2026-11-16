package io.github.kanabig.curtaincall.searchassist.client;

/** 외부 LLM으로 조건을 뽑는 추출기. 제공자마다 구현이 다르고 서비스는 하나만 쓴다. */
public interface LlmConditionExtractor extends ConditionExtractor {

    /** 호출할 수 있는 상태(키가 있고 mock으로 고정되지 않음). */
    boolean enabled();

    Usage usage();
}
