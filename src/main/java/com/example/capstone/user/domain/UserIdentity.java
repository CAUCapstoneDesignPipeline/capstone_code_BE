package com.example.capstone.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_identity")
public class UserIdentity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "text")
    private String provider;

    @Column(name = "provider_subject", nullable = false, columnDefinition = "text")
    private String providerSubject;

    @Column(columnDefinition = "text")
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserIdentity() {
    }

    public UserIdentity(UUID userId, String provider, String providerSubject,
            String email, boolean emailVerified, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.email = email;
        this.emailVerified = emailVerified;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public void loggedIn(Instant now) {
        this.lastLoginAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }
}
