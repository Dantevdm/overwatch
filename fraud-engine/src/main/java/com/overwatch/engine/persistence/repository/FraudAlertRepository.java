package com.overwatch.engine.persistence.repository;

import com.overwatch.common.persistence.FraudAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlertEntity, UUID> {
}
