package com.overwatch.api.streams;

import com.overwatch.api.streams.StreamsView.Group;
import com.overwatch.api.streams.StreamsView.Message;
import com.overwatch.api.streams.StreamsView.Topic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The stream, and two operations on it.
 *
 * <p>Reads are unconditional. Writes are behind
 * {@code overwatch.api.allow-stream-writes}, for the same reason the reset
 * endpoint is behind its own flag: they put messages on the pipeline's input
 * topic and nothing in this system authenticates. On for the demo, one line for
 * a deployment that wants the page read-only.
 */
@RestController
@RequestMapping("/api/streams")
@Tag(name = "Streams", description = "Kafka topics, consumer lag, and two operations on the stream")
public class StreamsController {

    private static final Logger log = LoggerFactory.getLogger(StreamsController.class);

    private final StreamsService streams;
    private final boolean allowWrites;

    public StreamsController(StreamsService streams,
                             @Value("${overwatch.api.allow-stream-writes:true}") boolean allowWrites) {
        this.streams = streams;
        this.allowWrites = allowWrites;
        if (!allowWrites) {
            log.info("Stream writes are disabled (overwatch.api.allow-stream-writes=false)");
        }
    }

    @GetMapping("/topics")
    @Operation(summary = "The topics this system owns, with per-partition offsets",
            description = """
                    Partition count, leader, earliest and latest offset, and the
                    retention each topic is kept under.

                    `messages` is `endOffset - startOffset`: what can be read right
                    now, not what has ever been produced. Once retention has deleted
                    a segment the two stop being the same number, and the offsets
                    are shown so that is visible rather than surprising.

                    Scoped to `transactions` and `fraud-alerts`. Redpanda's internal
                    topics are not this system's business — the Redpanda Console is
                    the tool for the broker itself.""")
    public List<Topic> topics() {
        return streams.topics();
    }

    @GetMapping("/groups")
    @Operation(summary = "Consumer groups, with per-partition lag",
            description = """
                    Lag is the end offset minus the group's committed offset,
                    computed here because the broker has no such field.

                    A partition the group has never committed on reports `-1` rather
                    than `0`. "Has not started" and "is caught up" are different
                    states, and a consumer that never came up should not look
                    healthy.

                    `state` is the broker's own: `STABLE` while consuming, `EMPTY`
                    when no member is connected.""")
    public List<Group> groups() {
        return streams.groups();
    }

    @GetMapping("/topics/{topic}/messages")
    @Operation(summary = "The most recent messages on a topic, newest first",
            description = """
                    Read by explicit partition assignment and seek, never by
                    subscribing: no group is joined, so the engine sees no
                    rebalance, and no offset is committed, so looking at a topic
                    cannot move anyone's position in it.

                    Payloads are returned exactly as stored, not deserialised and
                    re-serialised — including the epoch-seconds timestamps, which
                    is what is really on the wire.

                    `limit` applies per partition and then across the merged
                    result, and is capped at 100.""")
    public List<Message> messages(
            @PathVariable String topic,
            @RequestParam(defaultValue = "20")
            @Parameter(description = "How many messages to return. Capped at 100.")
            int limit) {
        return streams.peek(topic, limit);
    }

    @GetMapping("/template")
    @Operation(summary = "A valid transaction, ready to edit and publish",
            description = """
                    Built from the record the pipeline actually reads, with a fresh
                    id and a current timestamp — so the editor opens on something
                    the engine will accept rather than an example that went stale
                    the first time the contract changed.

                    The values are chosen to trip several rules at once: a large,
                    round, foreign, crypto-category amount. Publish it unedited and
                    an alert follows within a second.""")
    public Map<String, Object> template() {
        return streams.template();
    }

    @PostMapping(value = "/publish", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish one transaction onto the transactions topic",
            description = """
                    Takes a transaction as JSON, parses it to check it fits the
                    contract, and publishes the body verbatim under a key of its
                    `cardId`.

                    Verbatim matters: what lands on the topic is the text that was
                    sent, not a re-serialisation of it. The key matters too — the
                    topic is keyed by card so one card's transactions stay on one
                    partition and stay ordered, which is what the velocity rule
                    depends on.

                    A payload the contract rejects comes back as 400 with the
                    parser's own complaint. The pipeline does handle an
                    undeserialisable record correctly, but it does so three seconds
                    later in another service's log, which is no help to whoever
                    typed the field wrong.

                    Answers 403 when `overwatch.api.allow-stream-writes` is false.""")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody String body) {
        return guard(() -> streams.publish(body));
    }

    @PostMapping("/redeliver")
    @Operation(summary = "Re-publish recent messages verbatim, to prove redelivery is handled",
            description = """
                    Reads the last `count` messages off the transactions topic and
                    publishes them again, byte for byte, under their original keys.

                    This is the idempotency guard tested against the running system
                    rather than described. Kafka delivers at least once — a
                    rebalance, or a crash between processing a record and committing
                    its offset, and the same transaction arrives twice. The engine
                    skips each repeat on its primary key: watch
                    `transactions_redelivered_total` rise by `count` while the
                    transaction and alert totals stay where they were.

                    Answers 409 when the topic is empty, and 403 when
                    `overwatch.api.allow-stream-writes` is false.""")
    public ResponseEntity<Map<String, Object>> redeliver(
            @RequestParam(defaultValue = "10")
            @Parameter(description = "How many recent messages to re-publish. Capped at 100.")
            int count) {
        return guard(() -> streams.redeliver(count));
    }

    private ResponseEntity<Map<String, Object>> guard(java.util.function.Supplier<Map<String, Object>> action) {
        if (!allowWrites) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Stream writes are disabled on this instance.",
                    "detail", "Set overwatch.api.allow-stream-writes=true to enable them."));
        }
        return ResponseEntity.ok(action.get());
    }
}
