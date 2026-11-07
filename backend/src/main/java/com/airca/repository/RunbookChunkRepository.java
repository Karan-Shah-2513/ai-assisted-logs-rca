package com.airca.repository;

import com.airca.model.RunbookChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunbookChunkRepository extends JpaRepository<RunbookChunk, UUID> {

    List<RunbookChunk> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
