package io.github.kanabig.curtaincall.queue.repository;

/** 회차별 Redis 키. */
final class QueueKeys {

    static final String OPEN = "q:open";

    private QueueKeys() {}

    static String seq(long sid) {
        return "q:" + sid + ":seq";
    }

    static String waiting(long sid) {
        return "q:" + sid + ":waiting";
    }

    static String seen(long sid) {
        return "q:" + sid + ":seen";
    }

    static String active(long sid) {
        return "q:" + sid + ":active";
    }

    /** 같은 초에 입장 처리를 한 번만 하도록 잡는 키의 접두. 스크립트가 뒤에 epochSecond를 붙인다. */
    static String tickPrefix(long sid) {
        return "q:" + sid + ":tick:";
    }

    static String conf(long sid) {
        return "q:" + sid + ":conf";
    }
}
