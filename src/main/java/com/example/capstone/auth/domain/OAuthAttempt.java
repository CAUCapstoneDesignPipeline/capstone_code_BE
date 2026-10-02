package com.example.capstone.auth.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_attempt")
public class OAuthAttempt {
    @Id private UUID id;
    @Column(name = "state_hash", nullable = false, columnDefinition = "text") private String stateHash;
    @Column(name = "cookie_hash", nullable = false, columnDefinition = "text") private String cookieHash;
    @Column(nullable = false, columnDefinition = "text") private String nonce;
    @Column(name = "code_verifier", nullable = false, columnDefinition = "text") private String codeVerifier;
    @Column(name = "return_to", nullable = false, columnDefinition = "text") private String returnTo;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    protected OAuthAttempt() { }
    public OAuthAttempt(String stateHash, String cookieHash, String nonce, String verifier, String returnTo, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.stateHash = stateHash; this.cookieHash = cookieHash;
        this.nonce = nonce; this.codeVerifier = verifier; this.returnTo = returnTo; this.expiresAt = expiresAt;
    }
    public boolean claim(String cookieHash, Instant now) {
        if (!this.cookieHash.equals(cookieHash) || consumedAt != null || !expiresAt.isAfter(now)) { return false; }
        consumedAt = now; return true;
    }
    public String getNonce() { return nonce; }
    public String getCodeVerifier() { return codeVerifier; }
    public String getReturnTo() { return returnTo; }
}
