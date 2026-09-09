package com.overwatch.engine.persistence.repository;

import com.overwatch.common.persistence.FraudRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FraudRuleRepository extends JpaRepository<FraudRuleEntity, Long> {

    /** Everything the engine might run. DISABLED rows are not worth loading. */
    List<FraudRuleEntity> findByStateIn(List<String> states);
}
