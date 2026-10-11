package com.example.capstone.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.example.capstone.auth.domain.OAuthAttempt;

public interface OAuthAttemptRepository extends JpaRepository<OAuthAttempt, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAttempt> findByStateHash(String stateHash);
    @Modifying
    @Query("delete from OAuthAttempt a where a.expiresAt <= :now")
    int deleteExpired(Instant now);
}
