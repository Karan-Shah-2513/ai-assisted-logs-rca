package com.airca.repository;

import com.airca.model.Incident;
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

    @Modifying
    @Query(value = """
            INSERT INTO incidents (
                tenant_id, fingerprint_hash, service_name, raw_stack_trace,
                sanitized_stack_trace, occurrence_count, status, first_seen, last_seen
            )
            VALUES (
                :tenantId, :fingerprint, :serviceName, :rawStackTrace,
                :sanitizedStackTrace, 1, 'OPEN', now(), now()
            )
            ON CONFLICT (tenant_id, fingerprint_hash)
            DO UPDATE SET
                occurrence_count = incidents.occurrence_count + 1,
                last_seen = now()
            """, nativeQuery = true)
    int upsertIngestedIncident(
            @Param("tenantId") UUID tenantId,
            @Param("fingerprint") String fingerprint,
            @Param("serviceName") String serviceName,
            @Param("rawStackTrace") String rawStackTrace,
            @Param("sanitizedStackTrace") String sanitizedStackTrace);
}
