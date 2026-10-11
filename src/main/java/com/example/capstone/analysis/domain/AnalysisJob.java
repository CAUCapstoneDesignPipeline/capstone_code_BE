package com.example.capstone.analysis.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only mapping of public history fields; internal error text is not loaded. */
@Entity
@Table(name = "analysis_job")
@Immutable
public class AnalysisJob {
    @Id private UUID id;
    @Column(name = "note_id", nullable = false) private UUID noteId;
    @Column(name = "note_version", nullable = false) private int noteVersion;
    @Column(nullable = false, columnDefinition = "text") private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    protected AnalysisJob() { }

    public UUID getId() { return id; }
    public UUID getNoteId() { return noteId; }
    public int getNoteVersion() { return noteVersion; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
