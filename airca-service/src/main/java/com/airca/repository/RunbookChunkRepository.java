package com.airca.repository;

import com.airca.model.RunbookChunk;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunbookChunkRepository extends JpaRepository<RunbookChunk, UUID> {

    List<RunbookChunk> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<RunbookChunk> findByTenantIdAndSourceIncidentId(UUID tenantId, UUID sourceIncidentId);

    List<RunbookChunk> findAllByTenantIdAndSourceOrderByCreatedAtDesc(UUID tenantId, String source);

    List<RunbookChunk> findAllByTenantIdAndSourceAndCreatedAtBefore(
            UUID tenantId, String source, Instant cutoff);

    @Modifying
    @Query("DELETE FROM RunbookChunk c WHERE c.tenantId = :tenantId AND c.source = :source AND c.createdAt < :cutoff")
    int deleteByTenantIdAndSourceAndCreatedAtBefore(
            @Param("tenantId") UUID tenantId,
            @Param("source") String source,
            @Param("cutoff") Instant cutoff);
}

