package com.airca.repository;

import com.airca.model.Incident;
import com.airca.model.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    List<Incident> findAllByTenantIdOrderByLastSeenDesc(UUID tenantId);

    Optional<Incident> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Incident> findByTenantIdAndFingerprintHash(UUID tenantId, String fingerprintHash);

    long countByTenantId(UUID tenantId);

    List<Incident> findAllByTenantIdAndStatusAndLastSeenBefore(
            UUID tenantId, IncidentStatus status, Instant cutoff);

    @Modifying
    @Query("DELETE FROM Incident i WHERE i.tenantId = :tenantId AND i.status = :status AND i.lastSeen < :cutoff")
    int deleteByTenantIdAndStatusAndLastSeenBefore(
            @Param("tenantId") UUID tenantId,
            @Param("status") IncidentStatus status,
            @Param("cutoff") Instant cutoff);

    @Modifying
    @Query(value = """
            INSERT INTO incidents (
                tenant_id, fingerprint_hash, service_name, raw_stack_trace,
                sanitized_stack_trace, occurrence_count, status, first_seen, last_seen,
                embedding
            )
            VALUES (
                :tenantId, :fingerprint, :serviceName, :rawStackTrace,
                :sanitizedStackTrace, 1, 'OPEN', now(), now(),
                cast(:embedding as vector)
            )
            ON CONFLICT (tenant_id, fingerprint_hash)
            DO UPDATE SET
                occurrence_count = incidents.occurrence_count + 1,
                last_seen = now(),
                embedding = COALESCE(incidents.embedding, EXCLUDED.embedding)
            """, nativeQuery = true)
    int upsertIngestedIncident(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint,
            @Param("serviceName") String serviceName,
            @Param("rawStackTrace") String rawStackTrace,
            @Param("sanitizedStackTrace") String sanitizedStackTrace,
            @Param("embedding") String embedding);

    default int upsertIngestedIncident(
            UUID tenantId,
            String fingerprint,
            String serviceName,
            String rawStackTrace,
            String sanitizedStackTrace) {
        return upsertIngestedIncident(tenantId, fingerprint, serviceName, rawStackTrace, sanitizedStackTrace, null);
    }
}
