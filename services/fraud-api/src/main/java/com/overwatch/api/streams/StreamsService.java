package com.overwatch.api.streams;

import tools.jackson.databind.ObjectMapper;
import com.overwatch.api.streams.StreamsView.Group;
import com.overwatch.api.streams.StreamsView.GroupPartition;
import com.overwatch.api.streams.StreamsView.Message;
import com.overwatch.api.streams.StreamsView.Partition;
import com.overwatch.api.streams.StreamsView.Topic;
import com.overwatch.common.Topics;
import com.overwatch.common.domain.Transaction;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupType;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the broker on the Streams page's behalf, and performs its two write
 * operations.
 *
 * <p>The Redpanda Console already does topic browsing better than this ever will,
 * and it is linked from the sidebar. This exists for the part the console cannot
 * do: tie the stream to the pipeline that reads it. A reviewer looking at
 * consumer lag here can see, on the same screen, the transactions that lag is
 * made of and the alerts it produced — and can push a message through the topic
 * and watch it arrive.
 *
 * <p>Everything here is scoped to the two topics this system owns. Listing every
 * topic on the broker would mean listing Redpanda's internal ones, which are not
 * this system's business and are not what the page is for.
 */
@Service
public class StreamsService {

    private static final Logger log = LoggerFactory.getLogger(StreamsService.class);

    /**
     * What each topic is for. The broker knows names, partitions and offsets and
     * nothing about meaning; a list of names is not a data flow. Kept short —
     * the full contract lives in the topic documentation the Redpanda Console
     * serves, which this page links to.
     */
    private static final Map<String, String> ROLE = Map.of(
            Topics.TRANSACTIONS,
            "Every card transaction entering the system. The simulator produces here; "
                    + "the fraud engine is the only consumer.",
            Topics.FRAUD_ALERTS,
            "Alerts the engine raised, published for anything that wants to react. "
                    + "Nothing in this stack subscribes — it is the seam where "
                    + "notification or case management would attach.");

    /** Bounds every admin call, so a broker that has gone away fails a poll, not a page. */
    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(6);

    /** Bounds a peek. Reading the tail of two partitions should be immediate. */
    private static final Duration PEEK_TIMEOUT = Duration.ofSeconds(5);

    private static final int MAX_PEEK = 100;

    private final Admin admin;
    private final KafkaTemplate<String, String> kafka;
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper mapper;

    public StreamsService(Admin admin,
                          KafkaTemplate<String, String> rawKafkaTemplate,
                          KafkaProperties kafkaProperties,
                          ObjectMapper mapper) {
        this.admin = admin;
        this.kafka = rawKafkaTemplate;
        this.kafkaProperties = kafkaProperties;
        this.mapper = mapper;
    }

    // ---- reads ---------------------------------------------------------------

    /** Both topics, with per-partition offsets and the retention they are kept under. */
    public List<Topic> topics() {
        List<String> names = List.of(Topics.TRANSACTIONS, Topics.FRAUD_ALERTS);

        Map<String, TopicDescription> described =
                await(admin.describeTopics(names).allTopicNames());

        List<TopicPartition> partitions = described.values().stream()
                .flatMap(d -> d.partitions().stream()
                        .map(p -> new TopicPartition(d.name(), p.partition())))
                .toList();

        Map<TopicPartition, Long> start = offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> end = offsets(partitions, OffsetSpec.latest());
        Map<String, Config> configs = topicConfigs(names);

        List<Topic> out = new ArrayList<>();
        for (String name : names) {
            TopicDescription description = described.get(name);
            if (description == null) continue;

            List<Partition> parts = description.partitions().stream()
                    .map(info -> {
                        TopicPartition tp = new TopicPartition(name, info.partition());
                        long from = start.getOrDefault(tp, 0L);
                        long to = end.getOrDefault(tp, 0L);
                        return new Partition(info.partition(),
                                info.leader() == null ? -1 : info.leader().id(),
                                from, to, Math.max(0, to - from));
                    })
                    .sorted(Comparator.comparingInt(Partition::partition))
                    .toList();

            Config config = configs.get(name);
            out.add(new Topic(
                    name,
                    parts,
                    parts.stream().mapToLong(Partition::messages).sum(),
                    description.partitions().isEmpty()
                            ? 0 : description.partitions().getFirst().replicas().size(),
                    configValue(config, "cleanup.policy", "delete"),
                    retentionInWords(configValue(config, "retention.ms", "-1")),
                    ROLE.getOrDefault(name, "")));
        }
        return out;
    }

    /**
     * Consumer groups, with per-partition lag.
     *
     * <p>Lag is computed here rather than read from a broker field, because there
     * is no such field: it is the end offset minus the group's committed offset,
     * and both halves come from separate calls. A group that has never committed
     * for a partition reports {@code -1} rather than a lag, because "has not
     * started" and "is caught up" are different states and showing both as zero
     * hides a consumer that never came up.
     */
    public List<Group> groups() {
        List<String> ids = await(admin.listGroups().valid()).stream()
                .filter(StreamsService::isConsumerGroup)
                .map(GroupListing::groupId)
                .sorted()
                .toList();
        if (ids.isEmpty()) return List.of();

        Map<String, ConsumerGroupDescription> described =
                await(admin.describeConsumerGroups(ids).all());

        Map<String, ListConsumerGroupOffsetsSpec> specs = new HashMap<>();
        ids.forEach(id -> specs.put(id, new ListConsumerGroupOffsetsSpec()));
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed =
                await(admin.listConsumerGroupOffsets(specs).all());

        // One listOffsets call for every partition any group is committed on,
        // rather than one call per group: the end offset of a partition does not
        // depend on who is reading it.
        Set<TopicPartition> assigned = new TreeSet<>(
                Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition));
        committed.values().forEach(m -> assigned.addAll(m.keySet()));
        Map<TopicPartition, Long> end = offsets(List.copyOf(assigned), OffsetSpec.latest());

        List<Group> out = new ArrayList<>();
        for (String id : ids) {
            ConsumerGroupDescription description = described.get(id);
            Map<TopicPartition, OffsetAndMetadata> positions =
                    committed.getOrDefault(id, Map.of());

            List<GroupPartition> parts = positions.entrySet().stream()
                    .map(e -> {
                        long groupOffset = e.getValue() == null ? -1 : e.getValue().offset();
                        long endOffset = end.getOrDefault(e.getKey(), 0L);
                        return new GroupPartition(e.getKey().topic(), e.getKey().partition(),
                                groupOffset, endOffset,
                                groupOffset < 0 ? -1 : Math.max(0, endOffset - groupOffset));
                    })
                    .sorted(Comparator.comparing(GroupPartition::topic)
                            .thenComparingInt(GroupPartition::partition))
                    .toList();

            out.add(new Group(
                    id,
                    description == null ? "UNKNOWN" : String.valueOf(description.groupState()),
                    description == null ? 0 : description.members().size(),
                    description == null ? "" : description.partitionAssignor(),
                    parts.stream().filter(p -> p.lag() > 0).mapToLong(GroupPartition::lag).sum(),
                    parts));
        }
        return out;
    }

    /**
     * The most recent messages on a topic, newest first.
     *
     * <p>Reads by explicit assignment and seek rather than by subscribing. That is
     * the whole reason this is safe to call from a page that polls: no group is
     * joined, so no rebalance is triggered on the engine, and no offset is
     * committed, so looking at the topic cannot move anyone's position in it.
     *
     * <p>{@code limit} is applied per partition and then again across the merged
     * result, because "the last 20 messages" spread over two partitions is not
     * "the last 20 messages on partition 0".
     */
    public List<Message> peek(String topic, int limit) {
        requireKnownTopic(topic);
        int wanted = Math.clamp(limit, 1, MAX_PEEK);

        try (Consumer<String, String> consumer = peekConsumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);

            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            long expected = 0;
            for (TopicPartition tp : partitions) {
                long from = Math.max(beginning.getOrDefault(tp, 0L),
                        end.getOrDefault(tp, 0L) - wanted);
                consumer.seek(tp, from);
                expected += Math.max(0, end.getOrDefault(tp, 0L) - from);
            }
            if (expected == 0) return List.of();

            List<Message> collected = new ArrayList<>();
            long deadline = System.nanoTime() + PEEK_TIMEOUT.toNanos();
            while (collected.size() < expected && System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) continue;
                for (ConsumerRecord<String, String> record : records) {
                    collected.add(toMessage(record));
                }
            }

            return collected.stream()
                    .sorted(Comparator.comparing(Message::timestamp)
                            .thenComparingLong(Message::offset).reversed())
                    .limit(wanted)
                    .toList();
        } catch (org.apache.kafka.common.KafkaException e) {
            throw unavailable("read from", e);
        }
    }

    /**
     * A valid transaction, ready to be edited and published.
     *
     * <p>The page could ship a hardcoded example, and it would be wrong within a
     * day of anyone touching the record. This is built from the record itself and
     * carries a fresh id and a current timestamp, so what the editor opens with is
     * something the engine will actually accept.
     */
    public Map<String, Object> template() {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("id", UUID.randomUUID().toString());
        txn.put("cardId", "card-09999");
        txn.put("amount", 149_900.00);
        txn.put("currency", Transaction.DEFAULT_CURRENCY);
        txn.put("merchantName", "Hand-published example");
        txn.put("merchantCategory", "crypto");
        txn.put("countryCode", "GB");
        txn.put("channel", "ECOMMERCE");
        txn.put("timestamp", Instant.now().toString());
        txn.put("metadata", Map.of("source", "streams-page"));
        return txn;
    }

    // ---- writes --------------------------------------------------------------

    /**
     * Publish one hand-written transaction onto the transactions topic.
     *
     * <p>Parsed into {@link Transaction} before it is sent, and rejected with the
     * parser's own complaint if it does not fit. Publishing something the engine
     * cannot deserialise is a thing this pipeline handles correctly — the poison
     * record is counted and the partition keeps moving — but it fails silently
     * from the page's point of view, three seconds later, in another service's
     * logs. A 400 here says what is wrong while the reviewer is still looking at
     * the field they got wrong.
     *
     * <p>Sent verbatim after parsing, not re-serialised from the parsed object, so
     * what lands on the topic is the text on the screen.
     *
     * <p>Keyed on {@code cardId}, which is not incidental: the topic is keyed by
     * card so that one card's transactions land on one partition and are therefore
     * ordered. The velocity rule reads a card's recent history, and would see it
     * out of order otherwise.
     */
    public Map<String, Object> publish(String rawJson) {
        Transaction parsed;
        try {
            parsed = mapper.readValue(rawJson, Transaction.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Not a valid transaction: " + rootCause(e));
        }
        if (parsed.cardId() == null || parsed.cardId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cardId is required — it is the partition key, so a transaction "
                            + "without one cannot be ordered against the rest of its card.");
        }

        send(Topics.TRANSACTIONS, parsed.cardId(), rawJson);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", Topics.TRANSACTIONS);
        result.put("key", parsed.cardId());
        result.put("transactionId", parsed.id() == null ? null : parsed.id().toString());
        result.put("bytes", rawJson.getBytes(StandardCharsets.UTF_8).length);
        return result;
    }

    /**
     * Re-publish the last {@code count} messages on the transactions topic,
     * byte for byte, under their original keys.
     *
     * <p>This is the idempotency guard's test, run against the live system. Kafka
     * delivers at least once, so the engine must treat a repeat of a transaction
     * it has already seen as a no-op — and the way to show that is not a diagram,
     * it is to make the duplicates happen and then count the rows. The engine
     * skips each one on its primary key and increments
     * {@code transactions_redelivered_total}; the transaction and alert totals do
     * not move.
     *
     * <p>Original keys are preserved deliberately. Re-keying would put the copies
     * on different partitions from the originals, which would still be duplicates
     * but would no longer be the duplicates a rebalance actually produces.
     */
    public Map<String, Object> redeliver(int count) {
        List<Message> recent = peek(Topics.TRANSACTIONS, count);
        if (recent.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "There is nothing on " + Topics.TRANSACTIONS + " to re-deliver. "
                            + "Start the simulator, or publish a transaction first.");
        }

        for (Message message : recent) {
            send(Topics.TRANSACTIONS, message.key(), message.value());
        }
        log.info("Re-published {} messages onto {} to exercise the idempotency guard",
                recent.size(), Topics.TRANSACTIONS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("republished", recent.size());
        result.put("topic", Topics.TRANSACTIONS);
        result.put("keys", recent.stream().map(Message::key).distinct().sorted().toList());
        result.put("expectation", "The engine skips every one of these on its primary key. "
                + "transactions_redelivered_total rises by " + recent.size()
                + "; the transaction and alert totals do not move.");
        return result;
    }

    // ---- plumbing ------------------------------------------------------------

    private void send(String topic, String key, String value) {
        try {
            // Blocking, unlike the engine's fire-and-forget publish. This one is
            // answering an HTTP request: if the send fails, the caller has to be
            // told, and a 200 followed by a log line nobody reads is not telling
            // them.
            kafka.send(topic, key, value).get(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("publish to", e);
        } catch (ExecutionException | TimeoutException e) {
            throw unavailable("publish to", e);
        }
    }

    private Consumer<String, String> peekConsumer() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        // A distinct client id per peek so concurrent readers do not collide on
        // JMX registration. No group is joined — assign() is used, not
        // subscribe() — so the group id in configuration is never registered on
        // the broker and the engine sees no rebalance.
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "fraud-api-peek-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_PEEK);
        return new KafkaConsumer<>(props);
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
        if (partitions.isEmpty()) return Map.of();
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));
        Map<TopicPartition, ListOffsetsResultInfo> result = await(admin.listOffsets(request).all());
        Map<TopicPartition, Long> out = new HashMap<>();
        result.forEach((tp, info) -> out.put(tp, info.offset()));
        return out;
    }

    private Map<String, Config> topicConfigs(List<String> names) {
        List<ConfigResource> resources = names.stream()
                .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                .toList();
        Map<String, Config> out = new HashMap<>();
        await(admin.describeConfigs(resources).all())
                .forEach((resource, config) -> out.put(resource.name(), config));
        return out;
    }

    private static Message toMessage(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null
                    ? "" : new String(header.value(), StandardCharsets.UTF_8));
        }
        return new Message(
                record.partition(), record.offset(),
                Instant.ofEpochMilli(record.timestamp()),
                record.key(), record.value(),
                record.serializedValueSize(), headers);
    }

    private static boolean isConsumerGroup(GroupListing listing) {
        // Kafka 4 lists share and streams groups alongside consumer groups. Only
        // consumer groups have the lag this page is about. An absent type is
        // treated as one: older brokers do not report it, and a group with
        // committed offsets on our topics is worth showing either way.
        return listing.type()
                .map(type -> type == GroupType.CONSUMER || type == GroupType.CLASSIC)
                .orElse(true);
    }

    private static String configValue(Config config, String key, String fallback) {
        if (config == null) return fallback;
        ConfigEntry entry = config.get(key);
        return entry == null || entry.value() == null ? fallback : entry.value();
    }

    /** "7 days", "12 hours", "unbounded" — a retention window a person can read. */
    private static String retentionInWords(String millis) {
        long ms;
        try {
            ms = Long.parseLong(millis.trim());
        } catch (NumberFormatException e) {
            return millis;
        }
        if (ms < 0) return "unbounded";
        Duration d = Duration.ofMillis(ms);
        if (d.toDays() > 0 && d.toHours() % 24 == 0) {
            return d.toDays() + (d.toDays() == 1 ? " day" : " days");
        }
        if (d.toHours() > 0) return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
        return d.toMinutes() + " minutes";
    }

    private void requireKnownTopic(String topic) {
        if (!ROLE.containsKey(topic)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown topic '" + topic + "'. This endpoint covers the topics this "
                            + "system owns: " + String.join(", ", new TreeSet<>(ROLE.keySet()))
                            + ". Everything else on the broker is Redpanda's own, and the "
                            + "Redpanda Console is the tool for that.");
        }
    }

    private <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(ADMIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("reach", e);
        } catch (ExecutionException | TimeoutException e) {
            throw unavailable("reach", e);
        }
    }

    /**
     * A broker that is down is an ordinary state, not a server fault — the rest of
     * the API is useful without it. So it becomes a 503 carrying a sentence the
     * page can show, the same treatment an unreachable simulator gets.
     */
    private ResponseStatusException unavailable(String verb, Exception e) {
        log.warn("Could not {} Kafka: {}", verb, e.toString());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not " + verb + " the broker — " + rootCause(e));
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
