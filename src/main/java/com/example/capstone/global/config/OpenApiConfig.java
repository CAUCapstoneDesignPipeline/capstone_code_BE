package com.example.capstone.global.config;

import java.util.List;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("CAPSTONE Backend API")
                .version("0.3.0")
                .description("""
                        FE 연동 안내: 인증 → 주제 → 노트 순으로 펼쳐 요청 필드와 응답 설명을 확인하세요.

                        **개발 시작**: `GET /api/auth/providers`로 사용 가능한 로그인 수단을 확인합니다.
                        개발용 로그인이 활성화되어 있으면 `POST /api/auth/dev/token`에 `{}`를 입력하고,
                        응답의 `accessToken` 값만 상단 **Authorize**에 넣으세요. 주제·노트 API에는 Bearer 인증이 필요합니다.

                        **실제 로그인/재접속**: 소셜 로그인은 브라우저 이동으로 시작합니다.
                        refresh·logout은 FE에서 `credentials: include`로 호출하며,
                        브라우저 Origin이 `CAPSTONE_APP_URL`과 정확히 같아야 합니다.
                        Swagger 출처와 앱 출처가 다르면 이 두 API의 직접 실행은 403입니다.
                        HttpOnly 쿠키와 Origin을 Swagger 입력으로 대신할 수 없습니다.

                        **화면 처리**: 오류는 `error.code`로 구분하세요.
                        노트 저장 충돌의 `error.details.current`는 최신 노트이며 미저장 입력을 자동 덮어쓰지 않습니다.
                        시간은 UTC, ID는 UUID, `topicId: null`은 미분류입니다.
                        API별 Responses와 Schemas에서 필수·생략·null 조건을 확인하세요.

                        이 문서는 현재 BE 구현 설명입니다. 팀 계약 정본은 capstone_docs의 api/openapi.yaml입니다.
                        """))
                .tags(List.of(
                        new Tag().name("인증").description("로그인 수단 · 개발용 토큰 · Google 로그인 · 세션 갱신/로그아웃"),
                        new Tag().name("주제").description("본인 주제 트리 · 표시 순서 · 노트 집계 (Bearer 인증 필요)"),
                        new Tag().name("노트").description("본문 편집 · version 충돌 · 분류 이동 · 평문 검색 (Bearer 인증 필요)")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("발급 응답의 accessToken 값만 입력하세요. Bearer 접두사는 자동으로 붙습니다.")));
    }
}
