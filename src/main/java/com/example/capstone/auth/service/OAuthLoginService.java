package com.example.capstone.auth.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class OAuthLoginService {
    private final GoogleOAuthClient google;
    private final OAuthAttemptService attempts;
    private final IdentityLoginService identities;
    private final RefreshTokenService refresh;
    public OAuthLoginService(GoogleOAuthClient google, OAuthAttemptService attempts, IdentityLoginService identities,
            RefreshTokenService refresh) {
        this.google = google; this.attempts = attempts; this.identities = identities; this.refresh = refresh;
    }
    public void requireProvider(String provider) {
        if (!"google".equals(provider) || !google.enabled()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "지원하지 않는 로그인 방식입니다.");
        }
    }
    public Result complete(String state, String cookie, String code, String error) {
        var attempt = attempts.claim(state, cookie);
        if (attempt == null) { return Result.failed("oauth_failed"); }
        if (error != null) { return Result.failed("access_denied".equals(error) ? "access_denied" : "oauth_failed"); }
        boolean verified = false;
        try {
            var profile = google.exchange(code, attempt.getCodeVerifier(), attempt.getNonce());
            verified = profile.emailVerified();
            // Anyone with a provider-verified email may sign up; existing identities always log in.
            UUID id = identities.login("google", profile.subject(), profile.email(), verified, profile.name(),
                    verified && profile.email() != null);
            return new Result(refresh.issue(id), attempt.getReturnTo(), null);
        } catch (IdentityLoginService.SignupNotAllowedException exception) {
            return Result.failed("email_not_verified");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); return Result.failed("oauth_failed");
        } catch (Exception exception) { return Result.failed("oauth_failed"); }
    }
    public record Result(String refreshToken, String returnTo, String error) {
        static Result failed(String error) { return new Result(null,null,error); }
        @Override public String toString() { return "OAuthResult[successful=" + (error == null) + "]"; }
    }
}
