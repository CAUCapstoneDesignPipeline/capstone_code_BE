# CAPSTONE 백엔드 작업 지침

이 지침은 백엔드 저장소 전체에 적용한다. 캡스톤 서비스 개발과 팀 협업을 우선하며, 단일 모듈과 도메인별 패키지를 유지한다. 팀원용 상세 컨벤션은 [backend.md](../capstone_docs/conventions/backend.md), 환경 구성은 [백엔드 개발 가이드](../capstone_docs/development/backend-guide.md)를 참고한다.

## 대표 README 보호

- 루트 `README.md`는 포트폴리오 방문자가 프로젝트의 목적·핵심 기능·설계 특징·기술 스택을 빠르게 이해하는 대표 소개 문서다. 설명은 현재 코드로 확인할 수 있는 사실에 한정하고 구현된 기능과 계획을 구분한다.
- 사용자가 README 작성·수정 또는 대표 소개 내용 변경을 명시적으로 요청한 경우에만 수정한다. 일반 기능 개발, API 추가, 실행 설정 변경, 검증·문서 보완 요청을 README 수정 권한으로 해석하지 않는다.
- 실행 명령, 환경변수·키 발급 절차, Swagger 조작법, 문제 해결, 이슈별 진행 상황, 테스트 집계·로그, AI 작업 기록, 개인 컴퓨터 경로를 README에 추가하지 않는다.
- 실행·설정 안내는 팀 문서의 `development/backend-guide.md`, API별 설명은 Swagger와 팀 API 문서, 작업·검증 기록은 `.local/`에서 관리한다. 기능 개발의 문서 갱신은 이 위치에서 계속 수행하며 README 수정을 위해 작업을 중단하지 않는다.
- README 수정이 명시적으로 요청되어도 기존 소개 구조와 분량을 존중하고 요청한 범위만 바꾼다. 동작·계약·성과를 추측하거나 개발 매뉴얼을 다시 누적하지 않는다.

## 1. 작업 기준과 범위

- 작업 전 현재 경로, 적용되는 `AGENTS.md`, Git 상태와 관련 파일을 확인한다. 기존 사용자 변경을 보존하고 요청 범위만 수정한다.
- 기본 패키지는 `com.example.capstone`, Gradle 프로젝트 이름은 `capstone-be`다. 저장소 이름을 바꾸거나 프로젝트 폴더를 중첩하지 않는다.
- 현재 구현은 초기 설정·health API·공통 오류 응답·초기 ERD 전체의 V1 스키마다. 업무 기능·JPA 엔티티·인증은 해당 기능 요청에서 추가한다. 문서에 계획이 있다는 이유만으로 구현하지 않는다.
- 팀 문서 저장소를 함께 확인한다. 현재 로컬 이름은 `../capstone_docs`이며, 다른 클론 배치에서는 실제 docs 저장소 위치를 찾는다. 참조 문서가 없거나 계약끼리 충돌하면 추정으로 계약을 정하지 않고 필요한 부분만 확인한다.

| 판단 대상 | 기준 |
| --- | --- |
| 요청·응답·경로·외부 오류 코드 | docs의 `api/openapi.yaml` |
| 업무 동작·충돌 처리 | docs의 `api/rules.md` |
| 기능 범위·완료 조건·미정 사항 | docs의 `spec/functional_spec.md`, `TeamWorkDocs/` |
| 기록된 팀 결정 | docs의 `decisions/decisions.md` |
| DB 설계 / 실행 스키마 변경 | docs의 `diagrams/erd.md` / BE의 `src/main/resources/db/migration/` |
| 개발 컨벤션 | docs의 `conventions/backend.md`; 이 파일은 에이전트용 실행 요약 |
| 실제 의존성·실행 설정 | `build.gradle`, Wrapper properties, `compose.yml`, `application*.yml` |

API 계약과 동작 규칙이 기능명세서보다 우선한다. 새 팀 결정으로 계약을 바꿀 때는 관련 명세도 갱신한다. docs의 `api/` 변경은 FE·BE 승인을 받은 문서 PR이 먼저 병합된 뒤 구현 PR을 올린다. 전체 API는 주차와 관계없이 `api/openapi.yaml` 하나에서 관리한다. 미커밋 변경이나 `x-contract-status: draft`, `x-implementation-status: planned`, `(안)`·`미정` 표기를 확정 계약이나 구현 완료로 간주하지 않는다.

## 2. 스택과 설정

- Java 21 LTS, Spring Boot 4.1.1, Gradle Wrapper 8.14.5, PostgreSQL 이미지 `postgres:16.15`를 사용한다. 버전의 실제 값은 빌드·Wrapper·Compose 파일에서 확인하고 임의 변경하지 않는다.
- Spring Boot BOM이 관리하는 의존성은 개별 버전을 지정하지 않는다. BOM 밖 의존성이나 플러그인이 필요하면 버전과 선택 이유를 기록한다. 동적 버전, `latest`, SNAPSHOT은 사용하지 않는다.
- Boot 4에 맞는 starter와 import를 사용한다. MVC는 `spring-boot-starter-webmvc`, Flyway는 `spring-boot-starter-flyway`와 PostgreSQL 전용 모듈을 사용한다.
- Swagger UI는 `springdoc-openapi-starter-webmvc-ui:3.1.1`을 사용한다. Boot BOM 밖이라 버전을 명시하며 기본 `/swagger-ui.html`, `/v3/api-docs` 경로를 사용한다. 자동 생성 문서는 구현 확인용이고 전체 계약의 정본은 docs의 `api/openapi.yaml`이다.
- 필요가 확정되지 않은 Security, Redis, Kafka, QueryDSL, Lombok, DevTools, Spring AI, Docker Compose 자동 연동 등을 선제 추가하지 않는다.
- 공통 설정은 `application.yml`, 로컬 DB 연결은 `application-local.yml`에 둔다. `local`은 실행할 때 명시한다. SQL 출력·DEBUG 로그를 공통 설정에 넣지 않는다.
- 로컬 DB 변수는 `.env.example`의 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_HOST_PORT`를 공유한다. Compose의 `.env`는 호스트 Spring Boot에 자동 전달되지 않으므로 백엔드 개발 가이드의 export 절차를 따른다.
- 운영 연결은 표준 `SPRING_DATASOURCE_*` 환경변수를 사용한다. 실제 `.env`와 비밀번호를 출력·커밋하지 않으며, 변수 추가 시 `.env.example`과 백엔드 개발 가이드를 갱신한다.

## 3. 코드 구조와 네이밍

- 도메인별로 `com.example.capstone.<도메인>`에 둔다. `auth`, `user`, `topic`, `note`, `analysis`, `concept`, `evidence`, `relation`, `candidate`, `review` 아래 `controller`, `service`, `repository`, `domain`, `dto/request`, `dto/response`를 사용한다. 빈 말단 폴더는 `.gitkeep`으로 유지하고 실제 파일을 추가하면 그 폴더의 `.gitkeep`을 제거한다. 상태 확인은 `health/controller/HealthController.java`에 둔다. 폴더가 있다는 이유로 불필요한 클래스나 API를 만들지 않는다.
- 클래스·인터페이스·Enum 타입은 `UpperCamelCase`, 메서드·변수는 `lowerCamelCase`, 상수·Enum 값은 `UPPER_SNAKE_CASE`, 패키지는 소문자를 사용한다. Java 파일명은 타입명과 맞춘다.
- 역할 접미사는 `Controller`, `Service`, `Repository`, `Request`, `Response`, `Exception`, `Config`를 사용한다. 들여쓰기는 공백 4칸, 명시적 import, 기존 파일 스타일을 따른다.
- 의존성 주입은 생성자로 하고 의존 필드는 `final`로 둔다. 테스트의 `@Autowired` 주입은 허용한다.
- Controller는 입력 검증과 요청·응답 변환, Service는 업무 로직과 트랜잭션, Repository는 데이터 접근을 담당한다. 업무가 없는 health에 불필요한 Service를 추가하지 않는다.
- 업무 트랜잭션 경계는 Service에 두고 조회는 필요할 때 `readOnly = true`를 사용한다. 다중 저장 작업은 원자적으로 처리하고 외부 AI 호출을 긴 DB 트랜잭션에 묶지 않는다.
- 업무 요청·응답 DTO를 분리하고 엔티티를 직접 반환하지 않는다. 단순 DTO에는 Java record를 사용할 수 있다. 현재 health의 고정 Map 응답은 유지할 수 있다.
- 엔티티 상태는 의도가 드러나는 메서드로 변경한다. 공통 CRUD, 추상 베이스 클래스, `BaseTimeEntity`, 공통 응답 래퍼를 선제 도입하지 않는다.
- 기능 간 조회가 필요하면 공개 Service를 우선 사용한다. 불필요한 인터페이스·이벤트 계층을 만들지 않고 순환 의존은 책임과 호출 방향을 조정한다. 공유 인프라 코드는 실제 공통 사용이 생겼을 때 `global`로 분리한다.

## 4. API와 데이터 계약

- 업무 API는 현재 OpenAPI의 `/api` 기준을 따른다. 기존 `GET /api/v1/health`는 별도 요청 처리 확인 API로 HTTP 200, `{"status":"UP"}`를 반환한다. 이를 근거로 업무 API 전체에 `/v1`을 붙이지 않는다.
- DB 포함 상태 확인은 `/actuator/health`를 사용한다. Actuator 노출은 `health,info`, health 상세 정보와 컴포넌트는 숨김을 유지한다.
- 성공 JSON은 명세의 DTO 구조 그대로 반환한다. 오류는 `{"error":{"code":"...","message":"...","details":{...}}}` 계약을 따르며 `details`는 필요한 경우에만 포함한다. `ApiResponse<T>`를 강제하지 않는다.
- 외부 오류 코드는 OpenAPI의 의미 기반 코드(`NOTE_CONFLICT` 등)를 사용한다. `C001` 같은 별도 체계나 모든 예외를 같은 코드로 바꾸는 처리를 도입하지 않는다. 업무 오류는 `global.exception.ApiException`에 `ErrorCode`, 사용자용 한국어 문구, 필요한 details를 담아 던진다. 공통 `GlobalExceptionHandler`가 `global.response.ErrorResponse`로 변환한다. 내부 예외 메시지를 사용자용 문구로 전달하지 않는다. 무결성 오류의 도메인별 판별은 해당 기능에서 구현한다.
- `GlobalExceptionHandler`는 MVC 입력 오류·없는 경로·예상하지 못한 오류를 공통 형태로 변환한다. 프레임워크의 HTTP 상태와 `Allow` 등의 헤더를 유지한다. Security 필터의 오류 처리는 인증 기능을 구현할 때 별도로 같은 응답에 연결한다.
- `topicId: null` 같은 계약상 필수 null 값을 보존한다. null 필드 일괄 제외 설정을 추가하지 않는다. HTTP 상태와 사용자용 한국어 오류 문구는 해당 명세를 따른다.
- 시간은 RFC 3339 UTC(`2026-09-30T10:00:00Z`)로 전송한다. JVM 기본 시간대에 의존하지 않고 `Instant` 또는 UTC로 변환한 시간 타입을 사용한다.
- ID는 서버가 만드는 UUID다. 현재 주제·노트 목록 계약에는 페이지네이션이 없다. 새 페이징·정렬 규칙은 API 계약 변경으로 다룬다.
- 제목·본문 NFC 정규화, 제목 trim, 코드 포인트 기준 길이 검증, `/` 금지와 중복 범위는 명세를 따른다. `String.length()`나 기본 `@Size`만으로 코드 포인트 길이 조건을 충족했다고 보지 않는다.
- 노트 수정은 요청 version과 저장된 version을 비교하며 비교 이후의 동시 수정도 막는다. `@Version`만으로 요청의 오래된 version 검사를 대체하지 않는다. 노트 이동은 version을 바꾸지 않는 계약까지 함께 검증한다.

## 5. DB와 마이그레이션

- 데이터 접근은 Spring Data JPA를 기본으로 한다. 기본 CRUD는 `JpaRepository`, 단순 조건 조회는 `findByStatus` 같은 쿼리 메서드, 직접 정의하는 고정 조건 조회는 `@Query`의 JPQL을 사용한다. 읽기 쉬운 가장 단순한 방법을 선택하며 긴 메서드명이나 문자열 쿼리 조합을 억지로 유지하지 않는다.
- 여러 선택 조건을 조합하는 복잡한 동적 조회는 Querydsl 등을 검토한다. 현재 초기 구성에는 추가하지 않으며, 실제 기능 요구와 기존 방식의 한계를 확인한 뒤 호환 버전·Q 클래스 생성 설정·선택 이유를 기록하고 해당 조회에 필요한 Repository 구현만 추가한다.
- PostgreSQL 전용 기능이나 JPQL로 표현하기 어려운 특정 SQL은 네이티브 SQL을 사용할 수 있다. 사용 이유와 DB 의존성을 기록하고 입력값은 파라미터로 바인딩한다. 복잡한 조회나 성능 문제라는 이유만으로 전환하지 않고 실제 SQL·실행 계획을 확인한다.
- 조회는 기능의 Repository에 두며 Service의 업무 트랜잭션 경계를 유지한다. 어떤 조회 방식이든 사용자 소유 범위·수정 충돌·노트 이동의 version 규칙을 지킨다. 벌크 변경은 JPA 변경 감지·`@Version` 처리를 자동 적용하지 않으므로 영향 행 수와 영속성 컨텍스트 동기화를 검증한다.
- 쿼리 메서드·JPQL·Querydsl·네이티브 SQL의 결과와 필요한 조인·정렬·동시성은 PostgreSQL 통합 테스트로 검증한다. JDBC 직접 구현이나 MyBatis를 별도 요구 없이 추가하지 않는다. 상세 선택 기준은 docs의 `conventions/backend.md`를 따른다.
- 테이블·컬럼은 `snake_case`, JPA 필드는 `lowerCamelCase`다. Enum은 문자열로 저장하고 DB CHECK 값·API 문자열과 매핑을 맞춘다.
- `ddl-auto=validate`, `open-in-view=false`를 유지한다. 스키마는 Flyway로만 변경한다.
- `src/main/resources/db/migration/V{version}__{description}.sql`을 사용한다. 적용된 파일은 수정하지 않고 다음 버전을 추가하며 팀원의 번호와 중복되지 않게 확인한다.
- 초기 ERD의 19개 테이블은 BE의 `V1__initial_schema.sql`에 둔다. docs에는 ERD·설계 설명과 BE 링크만 두고 실행 SQL을 복제하지 않는다. 관계 유형·합성 규칙의 미정 seed는 추가하지 않는다. 엔티티보다 먼저 스키마를 정의할 수 있으며, 엔티티가 없는 테이블은 `ddl-auto=validate` 검증 대상이 아니므로 SQL 제약 테스트와 이후 엔티티 매핑 테스트를 구분한다.
- Flyway·JPA 비활성화, `schema.sql`·`data.sql` 병용, `baseline-on-migrate`·clean 허용으로 문제를 우회하지 않는다.
- 중복 검사는 DB 제약과 함께 보장한다. 동시 요청의 제약 위반도 계약에 맞는 오류로 변환하며, 미분류 제목 중복과 주제 삭제 충돌을 PostgreSQL에서 검증한다.
- 기존 DB·컨테이너·볼륨을 임의로 삭제하지 않는다. 검증은 별도 임시 리소스를 사용하고 해당 작업에서 생성한 임시 리소스만 정리한다.

## 6. 보안과 AI 연동

- 현재 인증 구현은 없다. 로그인·JWT·역할 모델을 가정해 코드를 추가하지 않는다. 다중 사용자 지원 시 사용자 범위와 객체 접근 권한을 계약부터 정한다.
- 비밀번호, 토큰, Authorization 헤더, API 키, 노트 원문과 AI 요청·응답 원문을 일반 애플리케이션 로그에 남기지 않는다.
- AI 연동 시 기능에 필요한 최소 데이터만 보내고 구조화 출력은 스키마·참조 ID·상태를 검증한 뒤 사용한다. 실험 데이터 취급은 AI 저장소의 합의된 절차를 따른다.
- BE는 데이터 저장과 서비스 흐름, 별도 AI 서버는 추출·검증 등 AI 처리를 담당한다. 연동 계약과 오류·시간 제한을 해당 기능에서 정하며 현재 코드에 AI SDK를 선제 추가하지 않는다.

## 7. 검증

모든 명령은 BE 루트에서 Java 21을 선택한 뒤 실행한다. macOS는 `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`, Linux는 설치된 JDK 21 경로를 사용한다. toolchain 선언만으로 JDK 설치를 가정하지 않는다.

```bash
./gradlew test
./gradlew test --tests 'com.example.capstone.health.controller.HealthControllerTest'
./gradlew testClasses
./gradlew bootJar
docker compose --env-file .env.example config --quiet
```

- 테스트는 `src/test/java`의 동일한 기능 패키지에 둔다. 새 단위 테스트는 `{대상}Test`, MVC는 `{대상}WebMvcTest`, 통합은 `{대상}IntegrationTest`를 권장한다. 기존 `HealthControllerTest`, `CapstoneApplicationTest`는 유지하며 실제 이름으로 실행한다.
- 단위·MVC 슬라이스는 DB와 Docker 없이 실행한다. Boot 4의 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`를 사용한다.
- DB 통합 테스트는 `@SpringBootTest`, Testcontainers `postgres:16.15`, `@ServiceConnection`을 사용한다. H2나 개발 DB로 대체하지 않고 `.env`, local 프로필, 고정 호스트 포트에 의존하지 않는다.
- 전체 `test`에 통합 테스트를 포함한다. Docker 미사용 시 조용히 건너뛰도록 설정하지 않는다. Docker가 불가능하면 MVC·컴파일·Jar 검증을 계속하고 전체 테스트 실패와 구분해 보고한다.
- 기능·버그 수정에는 동작을 검증하는 테스트를 추가한다. 문서만 변경하면 링크·기존 계약과의 일치·`git diff --check`를 확인하고 애플리케이션 테스트는 생략할 수 있다.
- 코드 변경 PR에는 전체 테스트 결과를 기록한다. 미검증 사유와 재실행 방법을 명시하고 부분 성공을 전체 성공으로 보고하지 않는다. DB 스키마와 마이그레이션을 바꾸면 PostgreSQL에서 적용 결과까지 검증한다.

## 8. 협업과 문서

- 팀 기준은 `main` 대상 PR, 1명 이상 승인, Squash merge다. 실제 원격 보호 규칙·CI가 설정되어 있는지는 별도 확인한다.
- 브랜치는 `타입/이슈번호-영역-내용`(예: `feat/10-note-update`)으로 작성하며 `/` 바로 뒤에 실제 관련 이슈 번호를 넣는다. 커밋 메시지는 `타입: 커밋 제목(#이슈번호)`(예: `feat: 공통 오류 응답 구현(#1)`), PR 제목은 `타입(#이슈번호): 내용`을 사용한다. 타입은 변경 목적에 맞게 `feat`, `fix`, `refactor`, `test`, `style`, `chore`, `docs` 중 선택한다. 콜론 뒤에는 공백 하나를 두고, 제목과 `(#이슈번호)` 사이에는 공백을 넣지 않는다. 브랜치·커밋·PR에 실제 관련 이슈 번호를 사용하며 번호를 임의로 만들지 않는다.
- 저장소의 `.github/ISSUE_TEMPLATE/feature-request.md`와 `.github/pull_request_template.md`를 따른다. 완료되는 이슈만 `Closes`, 참고는 `Related to`로 연결하며 타 저장소는 전체 이슈 주소를 쓴다.
- 이슈 제목·본문을 작성하거나 수정할 때는 로컬 보조 지침인 [.local/guides/issue-writing.md](.local/guides/issue-writing.md)를 먼저 읽고 현재 이슈 템플릿과 함께 적용한다. 이슈는 PR보다 훨씬 간결하게 작성한다. 이 지침은 Git에 포함하지 않으며, 다른 클론에 파일이 없으면 저장소 이슈 템플릿을 기준으로 목적·작업 범위·완료 조건만 짧게 정리한다.
- PR 제목·본문을 작성하거나 수정할 때는 로컬 보조 지침인 [.local/guides/pr-writing.md](.local/guides/pr-writing.md)를 먼저 읽고 현재 PR 템플릿과 함께 적용한다. 이 문서는 Git에 포함하지 않으며, 다른 클론에 파일이 없으면 이 문서의 협업·검증 기준과 저장소 PR 템플릿을 따른다.
- PR 본문은 목적 한 문장과 주요 변경 3~5개 항목을 기본으로 한다. 변경 항목은 `**분류명**: 변경 내용`으로 짧게 쓰고, 검증은 명령·절차와 실제 결과를 2~3개 항목으로 묶는다. API·DB 변경은 계약과 데이터 영향만 2~4개 항목으로 정리한다. 리뷰 참고사항은 필수 설정·선행 작업·판단할 쟁점만 보통 0~2개 남기고 없으면 삭제한다. 작은 변경은 더 짧게 쓰되 중요한 실패·미검증·호환성·데이터 영향은 생략하지 않는다. 파일별 나열, 중복 설명, 긴 로그, 일반적인 검토 요청과 근거 없는 성과 표현은 제거한다.
- 이슈 문서나 PR 문서 작성을 요청받으면 이슈는 `.local/issues/`, PR은 `.local/prs/`에 Markdown 파일로 저장한다. 폴더가 없으면 생성하고 파일명은 `issue-<내용>.md`, `pr-<내용>.md`처럼 영문 kebab-case로 정한다. 후속 수정은 해당 파일에 반영하고 저장 경로를 안내한다. `.gitignore`의 `/.local/` 규칙으로 폴더 전체를 제외하며, 이 문서들을 커밋하거나 강제로 추가하지 않는다.
- 설계·계약·컨벤션 등 팀원에게 공유할 대부분의 문서는 별도 `capstone_docs` 저장소에서 작성·수정한다. 백엔드 저장소의 `docs/`와 구분하며, 같은 문서의 사본을 양쪽에 만들지 않는다.
- AI가 작업 기록을 위해 만드는 검증 기록·조사 메모·작업 보고서와 공유 전 초안은 백엔드 저장소 루트의 `.local/`에 저장한다. 검증 보고서는 `.local/verification/reports/`, 빌드 로그는 `.local/verification/logs/`, 구조 검증 JSON은 `.local/verification/results/`, 기동 증거는 `.local/verification/runs/`에 구분한다. 초기 설정의 과거 기록은 [.local/verification/reports/setup-validation.md](.local/verification/reports/setup-validation.md)에서 관리하며, 이런 로컬 기록을 자동으로 `docs/`나 팀 문서 저장소에 옮기지 않는다.
- 백엔드 저장소의 `docs/`에는 사용자가 충분히 검토하고 포함하도록 명시적으로 요청한 매우 중요한 백엔드 문서만 추가한다. AI가 중요하다고 판단한 것만으로 추가하지 않으며, 검토용 초안은 먼저 `.local/`에 작성한다. 실행·설정 안내는 팀 문서의 `development/backend-guide.md`에서 갱신한다. 팀 공유 문서가 Git에서 제외된 로컬 기록을 필수 자료로 참조하지 않게 한다.
- `.local`의 작성 지침은 `guides/`, 재실행 도구는 `scripts/`, 변경 전 자료는 `snapshots/`에 둔다. 파일 배치는 [.local/README.md](.local/README.md)를 따르고 이동 시 문서 링크와 스크립트의 입력·출력 경로를 함께 수정한다.
- 컨벤션을 바꾸면 이 문서와 docs의 `conventions/backend.md`를 함께 갱신한다. 계약·코드 목록은 정본을 참조하며 별도 사본을 늘리지 않는다. `CLAUDE.md` 동기화나 자동 CI를 존재한다고 가정하지 않는다.
- 실행·설정 변경은 백엔드 개발 가이드와 필요한 예시 환경변수에 반영한다. 대표 README는 위 보호 규칙을 따른다. 새 일반 문서는 영문 kebab-case, 문서 내 링크는 가능한 상대 경로를 쓴다. `AGENTS.md`, `README.md` 등 표준 파일명은 유지한다.
- 요청 없는 커밋·푸시·배포·시스템 도구 설치는 하지 않는다. 변경 파일, 검증 결과, 남은 제약을 간결히 보고한다.
