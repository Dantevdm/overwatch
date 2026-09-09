package com.overwatch.api.repository;

import com.overwatch.common.persistence.FraudRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuleRepository extends JpaRepository<FraudRuleEntity, Long> {

    List<FraudRuleEntity> findAllByOrderByWeightDesc();

    /**
     * Rule state is the one field the API writes. Done as a modifying query
     * rather than a setter so the shared entity stays read-only from here — the
     * engine owns writes to everything else.
     */
    @Modifying
    @Query("UPDATE FraudRuleEntity r SET r.state = :state, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    int updateState(@Param("id") Long id, @Param("state") String state);

    @Modifying
    @Query("UPDATE FraudRuleEntity r SET r.weight = :weight, r.updatedAt = CURRENT_TIMESTAMP WHERE r.id = :id")
    int updateWeight(@Param("id") Long id, @Param("weight") java.math.BigDecimal weight);

    /** How often each rule has actually fired — the core of rule performance. */
    @Query("""
            SELECT h.ruleType, COUNT(h), AVG(h.weight)
            FROM AlertRuleHitEntity h GROUP BY h.ruleType
            """)
    List<Object[]> hitCountsByRuleType();

    /**
     * Confirmed and cleared counts per rule. This is what makes a false-positive
     * rate computable — without analyst disposition the number does not exist.
     */
    @Query("""
            SELECT h.ruleType,
                   SUM(CASE WHEN a.status = 'CONFIRMED' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN a.status = 'CLEARED'   THEN 1 ELSE 0 END)
            FROM AlertRuleHitEntity h JOIN h.alert a
            GROUP BY h.ruleType
            """)
    List<Object[]> dispositionByRuleType();

    @Query("SELECT s.ruleType, COUNT(s) FROM ShadowRuleHitEntity s GROUP BY s.ruleType")
    List<Object[]> shadowHitCounts();
}
