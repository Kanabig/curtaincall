package io.github.kanabig.curtaincall.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** 테스트에서 토픽을 만들고 읽는 도구. */
final class TestKafka {

    private TestKafka() {}

    static String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static void createTopic(String bootstrapServers, String topic, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all()
                    .get();
        }
    }

    /** 토픽을 처음부터 읽는다. 기대한 수만큼 모이거나 제한 시간이 지나면 멈춘다. */
    static List<ConsumerRecord<String, String>> readAll(
            String bootstrapServers, String topic, int expected, Duration timeout) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    /** 컨슈머 그룹이 커밋한 오프셋 합계. */
    static long committedOffsets(String bootstrapServers, String groupId, String topic) throws Exception {
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", bootstrapServers))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get();
            return offsets.entrySet().stream()
                    .filter(e -> e.getKey().topic().equals(topic))
                    .mapToLong(e -> e.getValue().offset())
                    .sum();
        }
    }
}
