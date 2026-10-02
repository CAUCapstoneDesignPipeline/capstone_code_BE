package com.example.capstone.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

public record DevTokenRequest(
        @Email @Schema(description = "개발 신원 식별 이메일. 생략/null은 dev@capstone.local. trim·소문자 변환 후 같은 이메일을 재사용합니다.", example = "dev@example.com")
        String email,
        @Schema(description = "표시 이름. 생략/null은 개발자. trim 후 1~100 코드 포인트입니다.", example = "개발자")
        String displayName,
        @Schema(description = "true이면 refresh HttpOnly 쿠키도 발급합니다. 생략/null/false이면 액세스 토큰만 발급합니다.", example = "false")
        Boolean issueRefreshCookie) {
}
