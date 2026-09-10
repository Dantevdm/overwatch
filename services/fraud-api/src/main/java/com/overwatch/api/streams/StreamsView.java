package com.overwatch.api.streams;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The shapes the Streams page reads.
 *
 * <p>Grouped in one file because they are one contract: five small records that
 * only ever travel together, and that mean nothing apart from the page they
 * describe. Splitting them across five files would spread one idea over five
 * places to look.
 */
public final class StreamsView {

    private StreamsView() {
    }

    /**
     * One partition of one topic.
     *
     * @param partition   partition number
     * @param leader      broker id serving it, or -1 when there is no leader
     * @param startOffset earliest offset still retained — non-zero once
     *                    retention has deleted a segment
     * @param endOffset   next offset to be written
     * @param messages    {@code endOffset - startOffset}: what is actually
     *                    readable now, which is not the number ever produced
     */
    public record Partition(
            int partition,
            int leader,
            long startOffset,
            long endOffset,
            long messages
    ) {
    }

    /**
     * A topic as it stands on the broker.
     *
     * @param name          topic name
     * @param partitions    per-partition offsets
     * @param messages      readable messages across all partitions
     * @param replication   replication factor of partition 0
     * @param cleanupPolicy {@code delete} or {@code compact}
     * @param retention     retention window in words, or "unbounded"
     * @param role          what this topic is for, in one line — the broker has
     *                      no idea, and a list of names is not a data flow
     */
    public record Topic(
            String name,
            List<Partition> partitions,
            long messages,
            int replication,
            String cleanupPolicy,
            String retention,
            String role
    ) {
    }

    /**
     * One partition's position for one consumer group.
     *
     * @param topic         topic name
     * @param partition     partition number
     * @param groupOffset   last committed offset, or -1 when the group has never
     *                      committed for this partition
     * @param endOffset     next offset to be written
     * @param lag           how far behind the group is, in messages
     */
    public record GroupPartition(
            String topic,
            int partition,
            long groupOffset,
            long endOffset,
            long lag
    ) {
    }

    /**
     * A consumer group.
     *
     * @param groupId    the group's id
     * @param state      broker-reported state — {@code STABLE} while consuming,
     *                   {@code EMPTY} when no member is connected
     * @param members    connected consumer count
     * @param assignor   partition assignment strategy in use
     * @param totalLag   summed lag across every assigned partition
     * @param partitions per-partition detail
     */
    public record Group(
            String groupId,
            String state,
            int members,
            String assignor,
            long totalLag,
            List<GroupPartition> partitions
    ) {
    }

    /**
     * One message, as it sits on the topic.
     *
     * @param partition partition it was written to
     * @param offset    its offset within that partition
     * @param timestamp broker or producer timestamp
     * @param key       record key — the partitioning decision, so worth showing
     * @param value     the raw payload, exactly as stored, not re-serialised
     * @param sizeBytes serialised value size
     * @param headers   record headers, decoded as UTF-8
     */
    public record Message(
            int partition,
            long offset,
            Instant timestamp,
            String key,
            String value,
            int sizeBytes,
            Map<String, String> headers
    ) {
    }
}
