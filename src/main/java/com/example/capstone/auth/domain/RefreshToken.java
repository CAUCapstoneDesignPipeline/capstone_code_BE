package com.example.capstone.auth.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_token")
public class RefreshToken {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "family_id", nullable = false)
    private UUID familyId;
    @Column(name = "token_hash", nullable = false, columnDefinition = "text")
    private String tokenHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "replaced_by_id")
    private UUID replacedById;
    @Column(name = "user_agent", columnDefinition = "text")
    private String userAgent;

    protected RefreshToken() {
    }

    public RefreshToken(UUID userId, UUID familyId, String hash, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = hash;
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(14 * 24 * 60 * 60);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean wasReplaced() { return replacedById != null; }
    public boolean isUsable(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }

    public void replaceWith(UUID nextId, Instant now) {
        this.revokedAt = now;
        this.replacedById = nextId;
    }
}
