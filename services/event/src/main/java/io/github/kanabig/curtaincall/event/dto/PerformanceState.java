package io.github.kanabig.curtaincall.event.dto;

/** 공연 상태. API는 영문 코드만 쓰고 KOPIS 원문(공연예정, 공연중, 공연완료)은 DB 조회와 응답 변환에만 쓴다. */
public enum PerformanceState {
    UPCOMING("공연예정"),
    ONGOING("공연중"),
    ENDED("공연완료");

    private final String kopis;

    PerformanceState(String kopis) {
        this.kopis = kopis;
    }

    public String kopis() {
        return kopis;
    }

    /** KOPIS 원문을 코드로. 모르는 값이면 null. */
    public static PerformanceState fromKopis(String kopis) {
        for (PerformanceState s : values()) {
            if (s.kopis.equals(kopis)) {
                return s;
            }
        }
        return null;
    }
}
