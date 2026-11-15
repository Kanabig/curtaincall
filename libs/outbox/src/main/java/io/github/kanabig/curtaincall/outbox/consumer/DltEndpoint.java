package io.github.kanabig.curtaincall.outbox.consumer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.lang.Nullable;

/**
 * DLT 조회와 재투입 관리 명령. 관리 포트의 {@code /actuator/dlt}로 연다.
 *
 * <ul>
 *   <li>{@code GET /actuator/dlt}: 이 서비스가 읽는 토픽의 DLT 이름
 *   <li>{@code GET /actuator/dlt/{dlt토픽}?limit=100}: DLT 메시지 요약(파티션, 오프셋, 원래 위치, 실패 사유)
 *   <li>{@code POST /actuator/dlt/{dlt토픽}} 본문 {@code {"offsets":"0:3,1:0"}}(파티션:오프셋, 쉼표로 구분): 선택한 메시지 재투입. {@code
 *       offsets}를 빼면 DLT 전체. 액추에이터 본문은 값이 문자열이어야 해서 배열을 쓰지 않는다
 * </ul>
 */
@Endpoint(id = "dlt")
public class DltEndpoint {

    private final DltReplayer replayer;
    private final KafkaListenerEndpointRegistry registry;
    private final String dltSuffix;

    public DltEndpoint(DltReplayer replayer, KafkaListenerEndpointRegistry registry, String dltSuffix) {
        this.replayer = replayer;
        this.registry = registry;
        this.dltSuffix = dltSuffix;
    }

    @ReadOperation
    public Set<String> topics() {
        Set<String> topics = new TreeSet<>();
        for (MessageListenerContainer container : registry.getAllListenerContainers()) {
            String[] listened = container.getContainerProperties().getTopics();
            if (listened != null) {
                for (String topic : listened) {
                    topics.add(topic + dltSuffix);
                }
            }
        }
        return topics;
    }

    @ReadOperation
    public List<DltReplayer.Entry> list(@Selector String topic, @Nullable Integer limit) {
        return replayer.list(topic, limit == null ? 100 : limit);
    }

    @WriteOperation
    public DltReplayer.Result replay(@Selector String topic, @Nullable String offsets) {
        Set<String> selected = offsets == null || offsets.isBlank()
                ? Set.of()
                : Arrays.stream(offsets.split(",")).map(String::trim).collect(Collectors.toSet());
        return replayer.replay(topic, selected);
    }
}
