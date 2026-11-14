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

    static String conf(long sid) {
        return "q:" + sid + ":conf";
    }
}
