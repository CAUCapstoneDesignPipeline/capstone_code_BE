package com.example.capstone.auth.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.capstone.auth.dto.response.MeResponse;
import com.example.capstone.auth.service.CurrentUserService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final CurrentUserService currentUsers;

    public AuthController(CurrentUserService currentUsers) {
        this.currentUsers = currentUsers;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return currentUsers.getMe(UUID.fromString(jwt.getSubject()));
    }
}
