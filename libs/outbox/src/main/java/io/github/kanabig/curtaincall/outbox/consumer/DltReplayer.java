package io.github.kanabig.curtaincall.outbox.consumer;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * DLT 메시지를 조회하고 원래 토픽으로 다시 넣는다. 컨슈머 그룹 없이 파티션을 직접 읽으므로 오프셋을 커밋하지 않고, DLT의 메시지도 지우지 않는다.
 *
 * <p>재투입은 원래 키·값·헤더(event_id가 든 봉투, X-Request-Id)를 그대로 보낸다. DLT 기록용 헤더({@code kafka_dlt-*})만 뗀다. 같은 메시지를 두 번
 * 넣어도 소비 측 {@code processed_event}가 두 번째를 건너뛴다.
 */
public class DltReplayer {

    private static final Logger log = LoggerFactory.getLogger(DltReplayer.class);
    private static final String DLT_HEADER_PREFIX = "kafka_dlt-";
    private static final Duration POLL = Duration.ofMillis(200);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_LIST = 1000;

    private final Supplier<Map<String, Object>> consumerProperties;
    private final KafkaOperations<String, String> producer;
    private final MeterRegistry meterRegistry;
    private final Duration sendTimeout;

    public DltReplayer(
            Supplier<Map<String, Object>> consumerProperties,
            KafkaOperations<String, String> producer,
            MeterRegistry meterRegistry,
            Duration sendTimeout) {
        this.consumerProperties = consumerProperties;
        this.producer = producer;
        this.meterRegistry = meterRegistry;
        this.sendTimeout = sendTimeout;
    }

    /** DLT에 있는 메시지 한 건의 요약. */
    public record Entry(
            int partition,
            long offset,
            String key,
            String originalTopic,
            Integer originalPartition,
            Long originalOffset,
            String requestId,
            String exception,
            long timestamp) {}

    /** 재투입 결과. 건너뛴 항목은 사유와 함께 돌려준다. */
    public record Result(int replayed, List<String> skipped) {}

    /** DLT의 메시지를 앞에서부터 최대 {@code limit}건(상한 1000) 조회한다. */
    public List<Entry> list(String dltTopic, int limit) {
        List<Entry> entries = new ArrayList<>();
        read(dltTopic, Math.min(Math.max(limit, 1), MAX_LIST), null, record -> entries.add(toEntry(record)));
        return entries;
    }

    /**
     * 선택한 메시지({@code "파티션:오프셋"}) 또는 {@code selected}가 비면 DLT 전체를 원래 토픽으로 다시 넣는다. 원래 토픽을 알 수 없거나 발행에
     * 실패한 메시지는 건너뛰고 사유를 남긴다.
     */
    public Result replay(String dltTopic, Set<String> selected) {
        Set<String> wanted = selected == null ? Set.of() : new HashSet<>(selected);
        List<String> skipped = new ArrayList<>();
        int[] replayed = {0};
        Set<String> found = new HashSet<>();
        read(dltTopic, Integer.MAX_VALUE, wanted, record -> {
            String id = record.partition() + ":" + record.offset();
            found.add(id);
            String original = headerValue(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
            if (original == null) {
                skipped.add(id + " 원래 토픽 헤더 없음");
                return;
            }
            try {
                producer.send(toOriginal(record, original)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                replayed[0]++;
                Counter.builder(MetricNames.OUTBOX_DLT_REPLAYED)
                        .description("DLT에서 원래 토픽으로 다시 넣은 메시지 수")
                        .tag("topic", original)
                        .register(meterRegistry)
                        .increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                skipped.add(id + " 발행 중단");
            } catch (Exception e) {
                log.warn("DLT 재투입 실패: {} {}", dltTopic, id, e);
                skipped.add(id + " 발행 실패: " + e.getClass().getSimpleName());
            }
        });
        for (String id : wanted) {
            if (!found.contains(id)) {
                skipped.add(id + " DLT에 없음");
            }
        }
        log.info("DLT 재투입: topic={} replayed={} skipped={}", dltTopic, replayed[0], skipped.size());
        return new Result(replayed[0], skipped);
    }

    private ProducerRecord<String, String> toOriginal(ConsumerRecord<String, String> record, String originalTopic) {
        RecordHeaders headers = new RecordHeaders();
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)) {
                headers.add(header);
            }
        }
        // 키가 같으면 원래와 같은 파티션으로 가서 키 단위 순서가 유지된다
        return new ProducerRecord<>(originalTopic, null, record.key(), record.value(), headers);
    }

    /** 파티션 순서대로 읽는다. {@code wanted}가 비어 있으면 전부, 아니면 그 위치만 {@code sink}에 넘긴다. */
    private void read(
            String dltTopic,
            int max,
            Set<String> wanted,
            java.util.function.Consumer<ConsumerRecord<String, String>> sink) {
        Properties properties = new Properties();
        properties.putAll(consumerProperties.get());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replay-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = consumer.partitionsFor(dltTopic, READ_TIMEOUT).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends =
                    new TreeMap<>(java.util.Comparator.comparingInt(TopicPartition::partition));
            ends.putAll(consumer.endOffsets(partitions, READ_TIMEOUT));
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            int count = 0;
            while (count < max && System.nanoTime() < deadline && !caughtUp(consumer, ends)) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL)) {
                    if (record.offset() >= ends.get(new TopicPartition(record.topic(), record.partition()))) {
                        continue;
                    }
                    String id = record.partition() + ":" + record.offset();
                    if ((wanted == null || wanted.isEmpty() || wanted.contains(id)) && count < max) {
                        sink.accept(record);
                        count++;
                    }
                }
            }
        }
    }

    private static boolean caughtUp(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    private static Entry toEntry(ConsumerRecord<String, String> record) {
        Long originalPartition = number(record, KafkaHeaders.DLT_ORIGINAL_PARTITION, 4);
        return new Entry(
                record.partition(),
                record.offset(),
                record.key(),
                headerValue(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                originalPartition == null ? null : originalPartition.intValue(),
                number(record, KafkaHeaders.DLT_ORIGINAL_OFFSET, 8),
                headerValue(record, "X-Request-Id"),
                headerValue(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.timestamp());
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        // Spring이 넣는 원래 토픽·예외 메시지는 UTF-8 문자열, 파티션·오프셋은 big-endian 숫자
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Spring이 넣는 원래 파티션(int)·오프셋(long) 헤더는 big-endian 숫자다. */
    private static Long number(ConsumerRecord<String, String> record, String name, int size) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != size) {
            return null;
        }
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(header.value());
        return size == 4 ? (long) buffer.getInt() : buffer.getLong();
    }
}
