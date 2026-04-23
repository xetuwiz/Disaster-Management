package com.sidms.backend.repository;

import com.sidms.backend.entity.BiasHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface BiasHistoryRepository extends JpaRepository<BiasHistory, Long> {

    List<BiasHistory> findByNodeIdAndVariableAndTimestampUtcAfter(
            UUID nodeId, String variable, LocalDateTime since);
}
