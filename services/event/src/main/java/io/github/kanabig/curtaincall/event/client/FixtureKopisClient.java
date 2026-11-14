package io.github.kanabig.curtaincall.event.client;

import io.github.kanabig.curtaincall.event.config.KopisModes;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * EVT-2. 키가 없을 때 쓰는 저장된 실제 응답(classpath {@code kopis/fixture}). 목록은 조회 기간과 무관하게 같은 파일을 첫 페이지에만 돌려준다. 같은 공연이
 * 여러 기간 창에서 오더라도 저장이 upsert라 결과가 같고, 외부 호출이 아니라 한도를 쓰지 않는다.
 */
@Component
@ConditionalOnExpression(KopisModes.FIXTURE)
public class FixtureKopisClient implements KopisClient {

    private static final String DIR = "kopis/fixture/";
    private static final String EMPTY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs></dbs>";

    @Override
    public boolean metered() {
        return false;
    }

    @Override
    public String listPerformances(LocalDate from, LocalDate to, int page, int rows, LocalDate afterDate) {
        return page == 1 ? read("pblprfr-list.xml") : EMPTY;
    }

    @Override
    public String performanceDetail(String mt20id) {
        return read("pblprfr-" + mt20id + ".xml");
    }

    @Override
    public String facilityDetail(String mt10id) {
        return read("prfplc-" + mt10id + ".xml");
    }

    private static String read(String name) {
        ClassPathResource resource = new ClassPathResource(DIR + name);
        if (!resource.exists()) {
            // 저장해 둔 응답에 없는 대상. 실제 KOPIS의 "없음"과 같게 빈 목록으로 답한다
            return EMPTY;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KopisCallException("fixture를 읽을 수 없다: " + name);
        }
    }
}
