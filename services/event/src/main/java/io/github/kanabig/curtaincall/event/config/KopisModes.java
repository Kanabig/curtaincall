package io.github.kanabig.curtaincall.event.config;

/** 클라이언트 구현 선택 조건. mode가 auto면 키가 있을 때 live, 없을 때 fixture다. */
public final class KopisModes {

    public static final String LIVE = "'${curtaincall.kopis.mode:auto}' == 'live'"
            + " or ('${curtaincall.kopis.mode:auto}' == 'auto' and !'${curtaincall.kopis.api-key:}'.isBlank())";
    public static final String FIXTURE = "!(" + LIVE + ")";

    private KopisModes() {}
}
