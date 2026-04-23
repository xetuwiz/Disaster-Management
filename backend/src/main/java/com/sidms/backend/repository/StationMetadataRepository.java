package com.sidms.backend.repository;

import com.sidms.backend.entity.StationMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationMetadataRepository extends JpaRepository<StationMetadata, String> {
}
