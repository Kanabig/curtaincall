package io.github.kanabig.curtaincall.outbox.message;

/**
 * eventType 버전 규칙. 필드 추가는 같은 타입으로 두고, 이름 변경·삭제·의미 변경은 버전을 올린다. 첫 버전은 접미사가 없고 두 번째부터
 * {@code PaymentApproved.v2}처럼 붙인다.
 */
public final class EventTypes {

    private static final String VERSION_MARKER = ".v";

    private EventTypes() {}

    public static String of(String name, int version) {
        if (version < 1) {
            throw new IllegalArgumentException("버전은 1 이상이다: " + version);
        }
        return version == 1 ? name : name + VERSION_MARKER + version;
    }

    public static String nameOf(String eventType) {
        int index = versionIndex(eventType);
        return index < 0 ? eventType : eventType.substring(0, index);
    }

    public static int versionOf(String eventType) {
        int index = versionIndex(eventType);
        return index < 0 ? 1 : Integer.parseInt(eventType.substring(index + VERSION_MARKER.length()));
    }

    private static int versionIndex(String eventType) {
        int index = eventType.lastIndexOf(VERSION_MARKER);
        if (index < 0) {
            return -1;
        }
        String number = eventType.substring(index + VERSION_MARKER.length());
        return !number.isEmpty() && number.chars().allMatch(Character::isDigit) ? index : -1;
    }
}
