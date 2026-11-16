package io.github.kanabig.curtaincall.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 이벤트·명령 봉투의 와이어 형식을 고정하는 골든 파일 도우미. 골든은 {@code contract/<이벤트 이름>.json}이고 봉투 전체 문자열(바이트)을 담는다.
 * 직렬화 라이브러리나 설정이 바뀌어도 이미 발행된 메시지와 새 메시지가 서로 읽히는지 이 파일로 확인한다.
 *
 * <p>다시 만들 때는 환경 변수 {@code CONTRACT_RECORD=<저장소 루트>}를 주고 테스트를 돌린다. 평소에는 비교만 한다.
 */
public final class EventContract {

    /** 골든 봉투에 쓰는 고정 값. */
    public static final String EVENT_ID = "00000000-0000-4000-8000-000000000001";

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T11:03:11.120Z"), ZoneId.of("Asia/Seoul"));

    private static final String RECORD_ENV = "CONTRACT_RECORD";

    private EventContract() {}

    /** 골든을 읽는다. */
    public static String golden(String name) {
        try (InputStream in = EventContract.class.getResourceAsStream("/contract/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("골든 파일이 없다: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 방금 직렬화한 봉투가 골든과 바이트까지 같은지 확인한다. 기록 모드에서는 골든을 덮어쓴다. */
    public static void assertMatches(String name, String actualEnvelope) {
        String root = System.getenv(RECORD_ENV);
        if (root != null) {
            Path file = Path.of(root, "libs/test-support/src/main/resources/contract", name + ".json");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, actualEnvelope + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }
        String expected = golden(name);
        if (!expected.equals(actualEnvelope)) {
            throw new AssertionError("와이어 형식이 골든과 다르다: " + name + "\n  골든: " + expected + "\n  실제: " + actualEnvelope);
        }
    }
}
