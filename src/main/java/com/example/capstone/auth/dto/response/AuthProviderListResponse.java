package com.example.capstone.auth.dto.response;

import java.util.List;

public record AuthProviderListResponse(List<Provider> providers, boolean devTokenEnabled) {
    public record Provider(String id, String name) { }
}
