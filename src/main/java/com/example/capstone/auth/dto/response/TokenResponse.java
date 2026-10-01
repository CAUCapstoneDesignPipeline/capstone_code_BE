package com.example.capstone.auth.dto.response;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {
    @Override
    public String toString() {
        return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
