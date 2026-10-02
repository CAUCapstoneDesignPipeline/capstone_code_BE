package com.example.capstone.auth.dto.request;

import jakarta.validation.constraints.Email;

public record DevTokenRequest(@Email String email, String displayName, Boolean issueRefreshCookie) {
}
