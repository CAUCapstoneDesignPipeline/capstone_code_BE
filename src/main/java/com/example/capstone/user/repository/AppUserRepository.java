package com.example.capstone.user.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.capstone.user.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
}
