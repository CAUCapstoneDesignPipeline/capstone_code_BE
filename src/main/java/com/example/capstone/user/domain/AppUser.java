package com.example.capstone.user.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(columnDefinition = "text")
    private String email;

    @Column(name = "display_name", nullable = false, columnDefinition = "text")
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected AppUser() {
    }

    public AppUser(String email, String displayName, Instant now) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.displayName = displayName;
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }
}
