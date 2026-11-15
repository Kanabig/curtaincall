package io.github.kanabig.curtaincall.queue.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 토픽별 DLT 이름. 기본은 {@code {토픽}.DLT}이고, 다른 서비스도 읽는 토픽만 {@code topics}에 서비스가 붙은 이름을 지정한다. 새 토픽을 읽게 되면 소비자가
 * 하나인지 여럿인지에 따라 여기에 적을지 정한다.
 *
 * @param topics 토픽 이름 → DLT 이름
 */
@ConfigurationProperties("curtaincall.queue.dlt")
public record DltProperties(@DefaultValue Map<String, String> topics) {

    public String dltTopic(String topic) {
        String named = topics.get(topic);
        return named != null ? named : topic + ".DLT";
    }
}
