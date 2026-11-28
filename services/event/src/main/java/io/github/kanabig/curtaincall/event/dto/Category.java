package io.github.kanabig.curtaincall.event.dto;

/** [EVT-4, EVT-5] KOPIS의 하위 분류. FESTIVAL은 응답에만 싣고 목록 조건으로는 받지 않는다. */
public enum Category {
    CHILD("is_child"),
    VISIT("is_visit"),
    LICENSE("is_license"),
    CREATIVE("is_creative"),
    DAEHAKRO("is_daehakro"),
    FESTIVAL("is_festival");

    private final String column;

    Category(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }
}
