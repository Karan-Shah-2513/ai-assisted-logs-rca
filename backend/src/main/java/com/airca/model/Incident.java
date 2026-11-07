package com.airca.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "incidents")
@Getter
@Setter
@NoArgsConstructor
public class Incident {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "fingerprint_hash", nullable = false, length = 64)
    private String fingerprintHash;

    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;

    @Column(name = "raw_stack_trace", nullable = false, columnDefinition = "text")
    private String rawStackTrace;

    @Column(name = "sanitized_stack_trace", nullable = false, columnDefinition = "text")
    private String sanitizedStackTrace;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_rca_json", columnDefinition = "jsonb")
    private JsonNode aiRcaJson;

    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
}
