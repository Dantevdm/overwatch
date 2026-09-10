package com.overwatch.api.repository;

import com.overwatch.common.persistence.TransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionReadRepository extends JpaRepository<TransactionEntity, UUID> {

    /**
     * Filtered transaction list. A null string parameter means "no filter", which
     * keeps this to one query instead of a Specification tree.
     *
     * <p>{@code since} is deliberately <em>not</em> nullable, and that is load
     * bearing rather than an inconsistency. PostgreSQL infers a parameter's type
     * from the context it appears in, and {@code :since IS NULL} supplies no
     * context at all — the driver sends the parameter untyped and the server
     * rejects the statement outright with "could not determine data type of
     * parameter". The string filters survive the same shape only because an
     * untyped parameter falls back to {@code text}, which happens to compare
     * correctly against a {@code VARCHAR} column. Nothing rescues a timestamp.
     * So the absence of a lower bound is expressed as {@link Instant#EPOCH} by
     * the caller, and the comparison stays unconditional and index-friendly.
     *
     * <p>The rule this leaves behind: never write {@code :param IS NULL} for a
     * parameter that is not a string.
     */
    @Query("""
            SELECT t FROM TransactionEntity t
            WHERE (:cardId     IS NULL OR t.cardId = :cardId)
              AND (:category   IS NULL OR t.merchantCategory = :category)
              AND (:customerId IS NULL OR t.customerId = :customerId)
              AND (LOWER(t.customerName) LIKE :customerPattern ESCAPE '\\')
              AND t.occurredAt >= :since
            ORDER BY t.occurredAt DESC
            """)
    Page<TransactionEntity> search(@Param("cardId") String cardId,
                                   @Param("category") String category,
                                   @Param("customerId") String customerId,
                                   @Param("customerPattern") String customerPattern,
                                   @Param("since") Instant since,
                                   Pageable pageable);

    @Query("""
            SELECT t.merchantCategory, COUNT(t) FROM TransactionEntity t
            GROUP BY t.merchantCategory ORDER BY COUNT(t) DESC
            """)
    List<Object[]> countByCategory();

    long countByOccurredAtAfter(Instant since);

    // ---- cardholder views ---------------------------------------------------

    /**
     * The cardholders this system has observed, most active first.
     *
     * <p>Derived from transactions rather than read from a customer table,
     * because there is no customer table and deliberately so — this system
     * observes a payment stream and does not own customer master data. A
     * cardholder with no transactions correctly does not exist here.
     *
     * <p>The name filter is a case-insensitive substring. Prefix-only would be
     * faster but is the wrong behaviour: people search for a surname.
     */
    /**
     * {@code pattern} is a ready-made LIKE pattern, already lowercased and already
     * wrapped in wildcards, and "no filter" is the single character {@code %}.
     *
     * <p>Not a nullable parameter with {@code :pattern IS NULL} beside it, and not
     * {@code CONCAT('%', :query, '%')} either. Both were tried and both fail, for
     * the same underlying reason in two different disguises: PostgreSQL infers a
     * parameter's type from its context, and neither construct gives it one.
     * {@code IS NULL} supplies nothing at all. {@code CONCAT} is worse than
     * nothing — it resolves the untyped parameter to {@code bytea}, and the query
     * dies at runtime on {@code function lower(bytea) does not exist}, which is
     * not a message that points anywhere near the cause.
     *
     * <p>Building the pattern in Java removes the choice from the planner. The
     * parameter appears only on the right of a {@code LIKE} against a text column,
     * where its type is unambiguous.
     *
     * <p>{@code ESCAPE} is declared explicitly because Hibernate otherwise emits
     * {@code escape ''}, under which a backslash is an ordinary character — so the
     * escaping the caller does would be silently inert and a typed underscore
     * would still match any character.
     */
    @Query("""
            SELECT t.customerId,
                   MAX(t.customerName),
                   COUNT(t),
                   SUM(t.amount),
                   MAX(t.occurredAt),
                   COUNT(DISTINCT t.cardId)
            FROM TransactionEntity t
            WHERE t.customerId IS NOT NULL
              AND (LOWER(t.customerName) LIKE :pattern ESCAPE '\\'
                   OR LOWER(t.customerId) LIKE :pattern ESCAPE '\\')
            GROUP BY t.customerId
            ORDER BY COUNT(t) DESC
            """)
    Page<Object[]> searchCustomers(@Param("pattern") String pattern, Pageable pageable);

    /**
     * Everything one cardholder has ever done, oldest first.
     *
     * <p>Unpaged on purpose: the profile computes aggregates across the whole
     * history — category mix, night-time share, per-card baselines — and an
     * aggregate over the first page of a history is not an aggregate. Bounded in
     * practice by the caller, which caps how much history a profile will read.
     */
    List<TransactionEntity> findByCustomerIdOrderByOccurredAtDesc(String customerId, Pageable pageable);

    long countByCustomerId(String customerId);

    /**
     * Transactions per bucket, for the volume the alert series is measured
     * against. Same bucketing as {@code AlertRepository.bucketedCounts} and
     * documented there: {@code date_bin} from the Unix epoch, so the two series
     * share boundaries exactly and can be read against one another.
     */
    @Query(value = """
            SELECT date_bin(make_interval(0, 0, 0, 0, 0, 0, CAST(:bucketSeconds AS double precision)),
                            occurred_at, TIMESTAMPTZ 'epoch') AS bucket,
                   COUNT(*)
            FROM transactions
            WHERE occurred_at >= :since
            GROUP BY bucket ORDER BY bucket
            """, nativeQuery = true)
    List<Object[]> bucketedCounts(@Param("since") Instant since,
                                  @Param("bucketSeconds") long bucketSeconds);
}
