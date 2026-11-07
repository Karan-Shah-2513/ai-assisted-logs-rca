package com.airca.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "runbook_chunks")
@Getter
@Setter
@NoArgsConstructor
public class RunbookChunk {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "runbook_name", nullable = false, length = 240)
    private String runbookName;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "source_path", length = 500)
    private String sourcePath;

    @Column(name = "git_diff", columnDefinition = "text")
    private String gitDiff;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
