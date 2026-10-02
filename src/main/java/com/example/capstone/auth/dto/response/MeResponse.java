package com.example.capstone.auth.dto.response;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID id, String email, String displayName, List<String> providers) {
}
