package com.sidms.backend.repository;

import com.sidms.backend.entity.SyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncStateRepository extends JpaRepository<SyncState, String> {
}
