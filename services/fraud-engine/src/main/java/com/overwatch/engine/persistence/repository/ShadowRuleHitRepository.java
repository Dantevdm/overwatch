package com.overwatch.engine.persistence.repository;

import com.overwatch.common.persistence.ShadowRuleHitEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShadowRuleHitRepository extends JpaRepository<ShadowRuleHitEntity, Long> {
}
