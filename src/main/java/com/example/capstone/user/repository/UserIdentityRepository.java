package com.example.capstone.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.capstone.user.domain.UserIdentity;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    List<UserIdentity> findByUserIdOrderByProvider(UUID userId);

    Optional<UserIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}
