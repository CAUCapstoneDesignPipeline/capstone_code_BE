# capstone-be

캡스톤 노트 서비스의 Spring Boot REST API 백엔드입니다. 단일 모듈과 도메인별 패키지를 사용합니다. 현재 초기 설정·health·공통 오류·V1 스키마와 JWT 인증 기반·내 정보 조회를 구현했습니다. refresh·로그인 발급 API와 주제·노트 API는 후속 작업입니다. 저장소 이름과 로컬 디렉터리 `capstone_code_BE`는 유지하고 Gradle 프로젝트 이름만 `capstone-be`로 지정했습니다.

개발 규칙은 [AGENTS.md](AGENTS.md)와 [팀 백엔드 컨벤션](../capstone_docs/conventions/backend.md)을 참고합니다.

## 기술 스택과 선택

| 항목 | 버전 / 구성 |
| --- | --- |
| Java | 21 LTS (Eclipse Temurin 권장) |
| Spring Boot | 4.1.1 |
| Gradle Wrapper | 8.14.5, Groovy DSL |
| PostgreSQL | 16.15, Docker 공식 이미지 `postgres:16.15` |
| 패키징 | 실행 가능한 Jar, 단일 모듈 |
| 기능 | Spring Web MVC, Validation, Data JPA, Actuator, Flyway, PostgreSQL JDBC |
| 인증 | Spring Security OAuth2 Resource Server, HS256 JWT (Boot BOM 관리) |
| API 문서 | springdoc-openapi 3.1.1, Swagger UI |
| 테스트 | JUnit Jupiter, MockMvc, Testcontainers JUnit / PostgreSQL, ServiceConnection |

Spring Boot 플러그인은 지정 버전 4.1.1을 명시합니다. Java 플러그인은 Gradle 내장 기능입니다. 추가 의존성 관리 플러그인 없이 Gradle의 `platform(SpringBootPlugin.BOM_COORDINATES)`로 Boot BOM을 가져오며, BOM이 관리하는 라이브러리 버전은 따로 지정하지 않습니다. 테스트에는 MVC·Security 테스트 starter와 `spring-boot-testcontainers`를 사용합니다. MVC 테스트 starter가 공통 Spring 테스트 지원과 JUnit Jupiter를 함께 제공합니다. PostgreSQL 전용 Flyway 모듈과 JDBC 드라이버는 실행 시 필요하므로 `runtimeOnly`입니다.

공식 자료와 확인 기록:

- [Boot 4.1.1 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html): Java 17–26, Gradle 8.14 이상인 8.x 또는 9.x. 지정한 Java 21 / Gradle 8.14.5가 범위에 포함됩니다.
- [Boot의 Gradle BOM 관리](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html), [관리 의존성 좌표](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html).
- [Boot MVC 테스트 문서](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html): Boot 4의 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` 사용.
- [Boot Testcontainers 문서](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html): `@ServiceConnection`으로 JDBC / Flyway 접속 정보를 컨테이너에서 제공.
- [Boot Flyway 초기화 문서](https://docs.spring.io/spring-boot/how-to/data-initialization.html): `spring-boot-starter-flyway`와 `flyway-database-postgresql` 필요.
- [Gradle 8.14.5 공식 릴리스](https://docs.gradle.org/8.14.5/release-notes.html), [Java 21 실행 지원](https://docs.gradle.org/8.5/release-notes.html).
- [PostgreSQL 16.15 릴리스](https://www.postgresql.org/docs/16/release-16-15.html), [Docker 공식 PostgreSQL 이미지](https://hub.docker.com/_/postgres).

2026-09-30에 [Spring Initializr](https://start.spring.io/)의 Boot 4.1.1 / Java 21 생성 결과로 starter 이름과 Testcontainers 좌표를 대조했습니다. 생성 결과의 Wrapper는 9.7.1이어서 사용하지 않았고, Gradle 공식 v8.14.5의 스크립트와 Wrapper Jar를 사용했습니다. 배포 ZIP의 공식 SHA-256도 Wrapper 설정에 고정했습니다. 이미지 태그 API에서 `16.15`와 Linux arm64 / amd64 지원을 확인했습니다.

## 디렉터리 구조

```text
capstone_code_BE/                   # 모든 아래 명령을 실행할 프로젝트 루트
├── build.gradle / settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/                 # Wrapper Jar / 배포 URL / 체크섬
├── compose.yml                    # PostgreSQL 서비스 하나
├── .env.example
├── src/main/java/com/example/capstone/
│   ├── CapstoneApplication.java
│   ├── health/controller/HealthController.java
│   ├── global/exception/         # 오류 코드·업무 예외·전역 처리기
│   ├── global/response/          # 공통 ErrorResponse
│   ├── auth/ · user/ · topic/ · note/ · analysis/
│   └── concept/ · evidence/ · relation/ · candidate/ · review/
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── db/migration/V1__initial_schema.sql
└── src/test/java/com/example/capstone/
    ├── CapstoneApplicationTest.java
    ├── global/exception/GlobalExceptionHandlerWebMvcTest.java
    └── health/controller/HealthControllerTest.java
```

각 업무 도메인에는 `controller`, `service`, `repository`, `domain`, `dto/request`, `dto/response` 폴더가 있으며 빈 말단 폴더는 `.gitkeep`으로 유지합니다. 실제 파일을 추가하면 해당 `.gitkeep`을 제거합니다. 테이블별 책임은 [팀 컨벤션](../capstone_docs/conventions/backend.md)의 도메인 표를 따릅니다.

## 개발 환경 준비

Java 21 JDK와 Docker CLI / 실행 중인 Docker 데몬, Docker Compose가 필요합니다. Docker는 로컬 DB와 통합 테스트에 필요하며 MVC 테스트와 Jar 빌드에는 필요하지 않습니다. Gradle을 별도로 설치할 필요는 없습니다. Wrapper의 첫 실행은 네트워크로 지정 배포본과 의존성을 다운로드합니다. Java toolchain 선언만으로 JDK가 설치되지는 않습니다.

macOS의 bash/zsh에서:

```bash
cd /Users/samso/Desktop/CAPSTONE/capstone_code_BE  # 자신의 클론 경로로 변경
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew --version
docker --version
docker info
docker compose version
```

Linux의 bash/zsh에서는 설치한 Java 21 JDK 경로를 지정합니다. 이후 명령은 macOS와 같습니다.

```bash
cd /path/to/capstone_code_BE
export JAVA_HOME=/path/to/temurin-21
export PATH="$JAVA_HOME/bin:$PATH"
java -version
./gradlew --version
```

Gradle 실행 JVM도 Java 21을 사용하세요. Java 26이 기본으로 설정된 환경에서는 위 `JAVA_HOME`을 먼저 설정해야 합니다. IDE의 프로젝트 JDK와 Gradle JVM도 21로 맞춥니다.

## 로컬 DB와 애플리케이션 실행

다음 명령은 모두 백엔드 프로젝트 루트에서 실행합니다. `.env`가 이미 있다면 복사하지 말고 팀원이 직접 기존 설정을 확인합니다.

```bash
cp -n .env.example .env
# .env를 편집해 POSTGRES_PASSWORD 예시를 자신의 로컬 비밀번호로 교체
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --wait postgres
docker compose --env-file .env ps
docker compose --env-file .env exec postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

`--wait`가 성공하면 healthcheck가 readiness를 확인한 것입니다. `pg_isready`는 서버의 접속 수락 여부를 확인하며, 애플리케이션 계정의 실제 SQL 실행은 통합 테스트나 애플리케이션 기동으로 확인합니다.

Compose의 `.env`는 YAML의 `${...}` 치환에 쓰입니다. 호스트에서 실행하는 Spring Boot에 자동으로 전달되지 않으므로 **애플리케이션을 실행할 터미널에서도** 아래처럼 export해야 합니다. `.env`는 셸 코드로 읽히므로 직접 관리하는 신뢰할 수 있는 파일만 읽으세요. `.env.example`은 bash/zsh 문법을 사용합니다.

```bash
# 백엔드 프로젝트 루트, Java 21 JAVA_HOME 설정 후
set -a
source ./.env
set +a
# .env에 키를 설정하지 않았다면, 로컬 세션용 무작위 키를 출력 없이 주입합니다.
# 재실행 시 같은 액세스 토큰을 쓰려면 동일한 개발용 키를 안전하게 보관·주입하세요.
if [ -z "$CAPSTONE_JWT_SECRET" ]; then
  export CAPSTONE_JWT_SECRET="$(openssl rand -base64 48)"
fi
./gradlew bootRun --args='--spring.profiles.active=local'
```

로컬 DB 이름·사용자·비밀번호·호스트 포트는 `.env`의 네 변수만 수정합니다. Compose와 `application-local.yml`이 모두 이 값을 참조합니다. DB는 `127.0.0.1`에만 바인딩하며 컨테이너 내부 포트는 5432입니다. Compose healthcheck의 `$$POSTGRES_USER` / `$$POSTGRES_DB`는 호스트 치환을 피하고 컨테이너 안에서 참조합니다.

공통 설정은 프로필을 자동 활성화하지 않습니다. `ddl-auto=validate`, `open-in-view=false`를 사용하고 Actuator는 `health,info`만 노출하며 health 상세 정보와 컴포넌트를 숨깁니다. SQL 출력과 DEBUG 로그는 기본으로 켜지 않습니다.

운영 환경에서는 `local` 프로필 없이 표준 환경변수 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 연결합니다. 비밀번호는 실행 환경에서 주입하며 파일이나 Git에 저장하지 않습니다. DataSource가 필요한 전체 애플리케이션은 DB 연결 없이 시작하도록 구성하지 않았습니다.

## 테스트와 Jar

모든 명령은 백엔드 프로젝트 루트에서 Java 21을 선택한 뒤 실행합니다.

```bash
./gradlew test                     # MVC + PostgreSQL 통합 테스트, Docker 데몬 필수
./gradlew test --tests 'com.example.capstone.health.controller.HealthControllerTest'  # DB / Docker 불필요
./gradlew testClasses              # 통합 테스트 코드도 컴파일, Docker 불필요
./gradlew bootJar                  # 테스트 실행과 별도인 Jar 빌드
```

통합 테스트는 새 `postgres:16.15` Testcontainers 컨테이너와 임의 호스트 포트를 사용합니다. `@SpringBootTest`로 전체 컨텍스트를 시작하고, 실제 DataSource에서 `SELECT 1`, V1 적용·재실행·체크섬, 19개 테이블과 주요 유일성·외래 키·CHECK 제약을 검증합니다. 로컬 프로필, `.env`, 개발 DB를 사용하지 않습니다. Docker가 없으면 통합 테스트는 **실패**하며 자동 건너뛰기를 설정하지 않았습니다. 테스트 터미널에서 개발용 프로필이나 별도의 `SPRING_FLYWAY_*` 설정을 export하지 마세요.

Flyway V1은 초기 ERD의 19개 테이블을 생성하며 `app_user`, `user_identity`는 JPA로 매핑했습니다. `ddl-auto=validate`는 매핑된 엔티티만 검사합니다. 인증 통합 테스트는 해당 매핑과 실제 JWT·내 정보 응답을 검증하고, 기존 SQL 테스트는 초기 스키마 제약을 검증합니다.

인증 기반의 필수 검증은 다음과 같이 선택할 수 있습니다. 이 부분 실행은 전체 회귀 테스트 결과를 대신하지 않습니다.

```bash
./gradlew test --tests 'com.example.capstone.auth.AuthFoundationIntegrationTest' \
  --tests 'com.example.capstone.auth.AuthProductionWebMvcTest' \
  --tests 'com.example.capstone.health.controller.HealthControllerTest' \
  --tests 'com.example.capstone.global.exception.GlobalExceptionHandlerWebMvcTest'
```

테스트의 `test` 프로필은 테스트 클래스패스의 합성 키를 사용합니다. 실제 `.env`나 Google 인증정보는 필요하지 않습니다. 이 키는 실행 Jar에 포함되지 않습니다.

Jar를 로컬 DB로 실행하려면 `.env`를 위 방식으로 export하고:

```bash
java -jar build/libs/capstone-be-0.0.1.jar --spring.profiles.active=local
```

## Health 확인

애플리케이션 실행 중 별도 터미널에서:

```bash
curl -i http://localhost:8080/api/v1/health
curl -i http://localhost:8080/actuator/health
```

- `/api/v1/health`: 요청 처리 확인용. HTTP 200과 `{"status":"UP"}` JSON만 반환합니다. DB 상태를 조회하지 않습니다.
- `/actuator/health`: DB를 포함한 애플리케이션 상태 확인용. DB 장애 등 상태에 따라 응답이 달라집니다. 기본 응답에서는 내부 상세 정보를 숨깁니다.

## Swagger UI

애플리케이션 실행 후 다음 주소로 접속합니다. 서버 포트를 바꿨다면 URL의 포트도 맞춥니다.

- Swagger UI: <http://localhost:8080/swagger-ui.html> (`/swagger-ui/index.html`로 이동)
- 자동 생성 OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- 자동 생성 OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>

Spring Boot 4를 지원하는 [springdoc 공식 안내](https://springdoc.org/)에 따라 `springdoc-openapi-starter-webmvc-ui:3.1.1`을 사용합니다. Boot BOM 밖의 의존성이므로 버전을 명시합니다. 별도 설정 클래스 없이 starter의 기본 경로와 자동 설정을 사용합니다.

Swagger UI는 현재 구현된 Controller를 기준으로 문서를 생성합니다. 팀의 전체 API 계약 정본은 [docs의 openapi.yaml](../capstone_docs/api/openapi.yaml)이며, 자동 생성 결과와 계약의 일치는 기능을 구현할 때 확인합니다.

## 공통 API 응답

성공 응답은 명세의 DTO를 그대로 반환합니다. 오류는 `global/response/ErrorResponse`의 `{"error":{"code":"...","message":"...","details":{...}}}` 구조를 사용하며 불필요한 `details`는 생략합니다. `ApiException`에 계약의 오류 코드·사용자용 문구·상세 정보를 담으면 `GlobalExceptionHandler`가 HTTP 응답으로 변환합니다. 사용 예와 MVC 기본 오류 처리 범위는 [팀 컨벤션](../capstone_docs/conventions/backend.md)의 API·응답·오류 절을 참고합니다.

## 인증 기반과 내 정보 조회

- `CAPSTONE_JWT_SECRET`은 필수이며, 32 UTF-8 바이트 이상의 무작위 문자열을 실행 환경에서 주입합니다. 설정 문자열을 UTF-8 키 바이트로 사용하며 별도로 Base64 디코딩하지 않습니다. 누락·짧은 키로는 기동하지 않습니다. 개발용·운영 키를 서로 다르게 관리하고 실제 키를 Git·로그에 남기지 않습니다.
- JWT는 HS256, `iss=capstone-be`, `sub=사용자 UUID`, `iat`·`exp`를 포함하며 30분 동안 유효합니다. 서명·issuer·필수 시각·UUID를 검증합니다. `Authorization: Bearer` 헤더로 인증하며 HTTP 세션이나 refresh 쿠키로 업무 API를 인증하지 않습니다.
- `GET /api/auth/me`는 `{id,email,displayName,providers}`를 반환합니다. nullable `email`도 응답에 포함합니다. 요청의 임의 `userId`로 사용자를 바꿀 수 없습니다. 누락·만료·변조 토큰은 JSON 401 `UNAUTHENTICATED`입니다.
- `dev` 신원의 액세스 토큰에는 저장된 신원을 기준으로 `dev=true`를 붙입니다. 활성 프로필이 `local`·`test`로만 구성된 경우에만 이 토큰을 허용하며, 운영이나 혼합 프로필에서는 `dev` 클레임이 있는 토큰을 거부합니다. 현재 공개 토큰 발급 API는 구현 전입니다.
- CORS는 `CAPSTONE_APP_URL` 하나만 허용하며 기본은 `http://localhost:5173`입니다. 경로·끝의 `/` 없이 Origin을 지정합니다. credentials·Authorization·Content-Type과 GET/POST/PUT/PATCH/DELETE/OPTIONS를 지원합니다.
- health의 두 경로는 공개이며, Swagger는 `local`·`test`에서 공개합니다. 인증 공개 경로는 메서드별로 providers·OAuth 시작/콜백·refresh·logout·dev/token만 지정하며 `/api/auth/me`와 나머지 요청은 보호합니다. 공개 경로 지정이 해당 API의 구현 완료를 뜻하지 않습니다.

운영은 `local`·`test` 없이 별도의 `CAPSTONE_JWT_SECRET`, 실제 앱 Origin `CAPSTONE_APP_URL`과 표준 DataSource 환경변수를 주입합니다. Google 로그인과 refresh·logout 설정은 해당 기능 구현 시 추가합니다.

## Flyway와 협업

[V1__initial_schema.sql](src/main/resources/db/migration/V1__initial_schema.sql)은 초기 ERD 전체를 생성합니다. 신규 빈 DB에 애플리케이션을 기동하면 Flyway가 JPA보다 먼저 적용합니다. 사용자·신원 엔티티는 이 스키마에 맞췄으며 나머지 엔티티는 기능 구현 시 추가합니다. 관계 유형과 합성 규칙의 미정 데이터는 seed로 넣지 않았습니다.

실행 SQL은 백엔드에서만 관리하고 docs에는 [ERD](../capstone_docs/diagrams/erd.md)와 설계 설명을 유지합니다. 적용된 V1은 수정하지 않고 다음 변경을 `V2__설명.sql`, `V3__설명.sql`로 추가합니다. 버전은 주차가 아닌 스키마 변경 순서이며 팀원의 번호와 겹치지 않게 조율합니다. `schema.sql` / `data.sql`을 병용하거나 `baseline-on-migrate`·clean 허용으로 오류를 우회하지 않습니다. 기존 DB에 같은 이름의 테이블이나 다른 V1이 있다면 실행을 멈추고 이력을 확인하며, 데이터를 삭제하거나 덮어쓰지 않습니다.

새 기능은 `com.example.capstone.<기능>` 아래에 필요한 코드만 추가합니다. 공통 CRUD 계층이나 응답 래퍼는 현재 만들지 않았습니다. API 계약과 협업 규칙은 [팀 문서 저장소](https://github.com/CAUCapstoneDesignPipeline/capstone_docs)를 따릅니다. 기능 변경 PR에는 관련 테스트 결과를 기록합니다.

## 자주 겪는 환경 문제

- Docker 데몬 연결 실패: Docker Desktop 등을 직접 실행한 뒤 `docker info`가 성공하는지 확인합니다.
- DB 포트 충돌: `.env`의 `POSTGRES_HOST_PORT`를 바꾸고 Compose를 다시 실행합니다. Spring Boot를 실행할 터미널에도 `.env`를 다시 export합니다.
- 애플리케이션 8080 포트 충돌: `bootRun --args='--spring.profiles.active=local --server.port=8081'`로 실행하고 curl 포트도 바꿉니다.
- 기존 named volume에서는 최초 초기화 때의 DB 이름·사용자·비밀번호가 유지됩니다. `.env` 값을 바꿔도 기존 DB 설정이 자동 변경되지 않습니다. 기존 데이터가 있으면 관리자와 조율해 DB 설정을 변경하거나 별도 Compose 프로젝트로 새 개발 DB를 구성하세요.
- PostgreSQL 16 데이터 볼륨은 `/var/lib/postgresql/data`에 연결합니다. `docker compose --env-file .env down`은 컨테이너를 종료하지만 named volume은 보존합니다. `down -v`나 Docker 전체 정리 명령을 일반 종료 용도로 쓰지 마세요.
- 실제 `.env`, Gradle 캐시, 빌드 결과, IDE 로컬 메타데이터는 `.gitignore` 대상입니다. `.env.example`과 Wrapper 파일은 Git에 포함합니다. 팀 공유 IDE 설정은 필요한 파일만 따로 검토합니다.

## Refresh와 로그아웃

`POST /api/auth/refresh`와 `POST /api/auth/logout`은 `Origin: CAPSTONE_APP_URL`을 요구합니다. Origin 누락·불일치는 토큰/쿠키 변경 전에 JSON 403 `FORBIDDEN`으로 거부합니다(사용자 승인 로컬 계약, 팀 정본 반영 대기). 액세스 토큰 헤더는 이 두 공개 경로의 인증에 사용하지 않습니다.

refresh 원문은 256비트 무작위 값이며 DB에는 SHA-256 해시만 저장합니다. 14일 동안 유효하고 사용할 때마다 회전합니다. 교체된 토큰 재사용은 해당 family 전체를 폐기한 뒤 401로 응답합니다. 사용자 행 잠금으로 회전·재사용·로그아웃을 직렬화합니다. 로그아웃은 현재 기기의 family만 폐기하며 이미 발급된 액세스 토큰은 만료까지 유효합니다.

쿠키는 `CAPSTONE_REFRESH`, HttpOnly, SameSite=Strict, Path=/api/auth입니다. Secure는 기본 활성화하고 local/test 전용 프로필과 HTTP 앱 Origin에서만 예외를 적용합니다. 정상 Origin의 비로그인 로그아웃도 204이며 동일 경로로 쿠키를 만료시킵니다.

### 개발용 로그인 (#5)

`local` 또는 `test` 프로필만 사용하고 `CAPSTONE_DEV_TOKEN_ENABLED=true`를 설정하면
`POST /api/auth/dev/token`이 열립니다. 기본값은 비활성이고 운영/혼합 프로필에서는 404입니다.
`GET /api/auth/providers`의 `devTokenEnabled`로 버튼 표시 여부를 확인합니다.
요청 예시: `{"email":"dev@capstone.local","displayName":"개발자","issueRefreshCookie":true}`.
필드를 생략하면 기본 개발 사용자를 재사용하며 refresh 쿠키는 요청한 경우에만 발급합니다.
응답의 `accessToken`을 Bearer 헤더로 `/api/auth/me`에 전달합니다.
개발용 서명 키는 운영 키와 다르게 설정해야 합니다. Google 제공자는 #6 설정 전까지 목록에 없습니다.

### Google 로그인 (#6)

Google OAuth 웹 클라이언트를 만들고 승인된 redirect URI를 정확히
`http://localhost:8080/api/auth/oauth2/google/callback`로 등록합니다. 배포 시에는
HTTPS BE 주소의 같은 경로를 등록하고 `CAPSTONE_GOOGLE_REDIRECT_URI`도 일치시킵니다.
`CAPSTONE_GOOGLE_CLIENT_ID`, `CAPSTONE_GOOGLE_CLIENT_SECRET`을 환경변수로 주입하고
`CAPSTONE_ALLOWED_EMAILS`에 가입 가능한 이메일을 쉼표로 구분하여 입력합니다.
비밀 값은 파일·로그·Git에 저장하지 않습니다. `CAPSTONE_GOOGLE_ENABLED=true`로 활성화하며
필수 설정이 빠지면 기동에 실패합니다. 비활성 상태에서는 목록이 비어 있고 OAuth 경로는 404입니다.
`CAPSTONE_APP_URL`은 경로 없는 앱 Origin(기본 `http://localhost:5173`)이며 CORS/refresh Origin과 같습니다.

브라우저에서 제공자 목록 확인 → `/api/auth/oauth2/google?returnTo=/` 이동 →
허용 계정 동의 → 앱 `/auth/callback`에서 credentials 포함 refresh → 내 정보 조회 순으로 확인합니다.
이후 취소·미허용 계정·동일 계정 재로그인·팝업 재로그인을 확인합니다.
실제 Google/브라우저 검증은 인증정보 주입 후 #14에서 수행합니다.
자동 테스트는 테스트용 RSA 서명·JWKS·HTTP 제공자를 사용하며 실제 ID 토큰 검증 로직을 실행합니다.
구현 기준: [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect).

OAuth state/브라우저 쿠키는 독립적인 무작위 값이며 DB에는 해시를 보관합니다.
V2는 5분 만료·일회용 시도와 nonce/PKCE verifier를 저장하며 새 시도 생성 시 만료 행을 정리합니다.
임시 쿠키는 HttpOnly/Lax이고 refresh는 Strict입니다. 외부 코드 교환은 DB 트랜잭션 밖에서 수행합니다.
returnTo는 사용자 승인 로컬 기준으로 내부 경로만 허용하며 잘못된 값은 400 VALIDATION_FAILED입니다(팀 정본 반영 대기).
