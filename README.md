# CAPSTONE Backend

학습 노트에 흩어진 개념과 관계를 연결하고, 새로운 연결을 원문 근거와 함께 제안하는 **CAPSTONE**의 백엔드입니다.

사용자 인증, 주제·노트 관리, 데이터 저장을 담당하는 Spring Boot REST API 서버를 개발합니다. 현재 인증과 노트 관리 API를 구현했으며, 개념 추출·관계 탐색·지식 그래프 반영은 별도 AI 서버와 연계할 후속 개발 범위입니다.

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 사용자 인증 | Google OAuth 로그인, JWT 인증, refresh 세션 갱신과 기기별 로그아웃 |
| 주제 관리 | 주제 생성·이름 변경·표시 순서 저장, 주제별 노트 수 집계 |
| 노트 관리 | 원문 생성·조회·수정·삭제, 주제 이동과 미분류 관리 |
| 노트 검색 | 제목·본문 부분 검색, 주제 필터와 정렬, 검색 문맥 미리보기 |

## 설계의 핵심

- **사용자별 데이터 분리**: 인증된 사용자를 기준으로 주제·노트의 조회와 변경 범위를 제한합니다.
- **동시 편집 보호**: 요청 version과 JPA 낙관적 잠금을 함께 사용해 오래된 저장 요청을 거절하고 최신 내용을 반환합니다.
- **삭제의 원자성**: 주제를 삭제하면 노트를 미분류로 이동하며, 제목 충돌이 발생하면 삭제와 이동을 함께 취소합니다.
- **세션 관리**: refresh 토큰은 해시로 저장하고 사용마다 교체합니다. 재사용을 탐지하면 해당 세션을 폐기합니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| 언어·프레임워크 | Java 21 · Spring Boot 4.1.1 · Spring MVC |
| 인증 | Spring Security · OAuth 2.0 / OpenID Connect · JWT |
| 데이터 | PostgreSQL 16 · Spring Data JPA · Flyway |
| API 문서 | OpenAPI · Swagger UI |
| 테스트·빌드 | JUnit · MockMvc · Testcontainers · Gradle |

## 코드와 검증

도메인별 패키지 안에서 Controller·Service·Repository의 책임을 나눕니다. 업무 트랜잭션은 Service에서 관리하고, DB 변경은 Flyway 마이그레이션으로 추적합니다.

실제 PostgreSQL을 사용하는 통합 테스트로 사용자 격리, 동시 저장·이동, 삭제 롤백을 검증합니다. [서버 재시작 시나리오](src/test/java/com/example/capstone/BasicApiAcceptanceIntegrationTest.java)에서는 저장 데이터와 유효한 로그인 세션의 복원을 확인합니다.

## 관련 자료

- [프로젝트 소개와 설계 문서](https://github.com/CAUCapstoneDesignPipeline/capstone_docs)
- [API 명세](https://github.com/CAUCapstoneDesignPipeline/capstone_docs/blob/main/api/openapi.yaml)
- [DB 마이그레이션](src/main/resources/db/migration/)
- [백엔드 코드](src/main/java/com/example/capstone/)
