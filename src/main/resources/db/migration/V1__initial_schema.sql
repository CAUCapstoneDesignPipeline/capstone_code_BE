-- 스키마 변경의 정본. 적용 후에는 수정하지 않고 V2 이후로 변경한다.
-- JPA 엔티티와 업무 API는 별도 구현한다. 미정인 관계 유형·합성 규칙의 seed는 포함하지 않는다.
-- V1: 초기 ERD 전체 (사용자·인증·주제·노트·분석·그래프·후보·검토)
-- 설계 문서: capstone_docs/diagrams/erd.md
-- PostgreSQL 16. gen_random_uuid()는 13부터, UNIQUE NULLS NOT DISTINCT와 ON DELETE SET NULL (열 목록)은 15부터 쓸 수 있다.
-- 글자 수는 char_length(유니코드 코드 포인트)로 센다. Java에서 검사할 때도 String.length()가 아니라 codePointCount를 쓴다.
--
-- 로그인 (erd.md E6, E9~E12)
-- - 소셜 로그인만 지원한다. 첫 제공자는 구글. 제공자 추가 시 다음 마이그레이션에서 user_identity.provider CHECK 목록을 확장한다.
-- - 비밀번호를 저장하지 않는다. 가입은 허용 목록(환경변수)에 있는 이메일만 된다. 계정 하나로 시작한다.
-- - BE가 발급하는 액세스 토큰(JWT)은 저장하지 않는다. refresh 토큰은 해시만 저장한다.
-- - 개발용 토큰은 local·test 프로필에서만 발급한다. 개발 사용자는 provider = 'dev' 신원으로 만든다.

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text,                                          -- 제공자가 준 대표 이메일 (표시·가입 허용 확인용)
    display_name  text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    CONSTRAINT app_user_display_name_length CHECK (char_length(display_name) BETWEEN 1 AND 100)
);

-- 소셜 로그인 신원. 사용자 하나에 제공자별로 하나씩 붙는다.
CREATE TABLE user_identity (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    provider         text        NOT NULL,                       -- google, 이후 kakao·github 등. dev는 개발용
    provider_subject text        NOT NULL,                       -- 제공자의 사용자 고유 id (OIDC sub). 이메일이 아니다
    email            text,
    email_verified   boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    -- 새 제공자를 더할 때는 이 목록에 값을 추가하는 마이그레이션만 올린다
    CONSTRAINT user_identity_provider CHECK (provider IN ('google', 'dev')),
    CONSTRAINT user_identity_subject_unique  UNIQUE (provider, provider_subject),
    CONSTRAINT user_identity_user_provider_unique UNIQUE (user_id, provider)
);

-- refresh 토큰. 기기(브라우저)마다 한 줄. 쓸 때마다 새 토큰으로 바꾸고(rotation), 이전 토큰을 다시 쓰면 그 계열을 모두 끊는다.
CREATE TABLE refresh_token (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id      uuid        NOT NULL,                         -- 같은 로그인에서 이어진 토큰 묶음
    token_hash     text        NOT NULL,                         -- SHA-256(토큰). 원문은 저장하지 않는다
    created_at     timestamptz NOT NULL DEFAULT now(),
    expires_at     timestamptz NOT NULL,
    revoked_at     timestamptz,                                  -- 로그아웃·교체·재사용 감지
    replaced_by_id uuid        REFERENCES refresh_token (id) ON DELETE SET NULL,
    user_agent     text,                                         -- 기기 구분용 (선택)
    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT refresh_token_expiry CHECK (expires_at > created_at)
);

CREATE INDEX refresh_token_user_idx   ON refresh_token (user_id);
CREATE INDEX refresh_token_family_idx ON refresh_token (family_id);

CREATE TABLE topic (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name        text        NOT NULL,
    sort_order  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT topic_user_name_unique UNIQUE (user_id, name),    -- 대소문자 구분 (E2)
    CONSTRAINT topic_id_user_unique   UNIQUE (id, user_id),      -- note의 복합 FK 대상
    CONSTRAINT topic_name_length      CHECK (char_length(name) BETWEEN 1 AND 50),
    CONSTRAINT topic_name_no_slash    CHECK (strpos(name, '/') = 0)
);

-- sort_order는 UNIQUE로 묶지 않는다. 순서 변경(PUT /topics/order)은 한 트랜잭션에서 사용자의 모든 주제에
-- 0부터 다시 번호를 매기고, 새 주제는 최댓값 + 1을 받는다.
CREATE INDEX topic_user_sort_idx ON topic (user_id, sort_order, name);

CREATE TABLE note (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    topic_id    uuid,                                                -- NULL = 미분류
    title       text        NOT NULL,
    body        text        NOT NULL DEFAULT '',                     -- 마크다운 원문 (NFC)
    version     integer     NOT NULL DEFAULT 0,                      -- 제목·본문 수정마다 +1, 이동에는 그대로
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),                  -- 이동해도 바뀐다 (E3)
    -- 노트와 주제의 사용자가 같아야 한다. 주제를 지우면 topic_id만 NULL(미분류)로 바꾸고 user_id는 둔다.
    CONSTRAINT note_topic_fk FOREIGN KEY (topic_id, user_id)
        REFERENCES topic (id, user_id) ON DELETE SET NULL (topic_id),
    CONSTRAINT note_title_length    CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT note_title_no_slash  CHECK (strpos(title, '/') = 0),
    CONSTRAINT note_body_length     CHECK (char_length(body) <= 1000000),
    CONSTRAINT note_version_nonneg  CHECK (version >= 0),
    -- 같은 주제 안에서 제목 유일. 같은 사용자의 미분류(topic_id NULL)끼리도 겹칠 수 없다.
    CONSTRAINT note_topic_title_unique UNIQUE NULLS NOT DISTINCT (user_id, topic_id, title)
);

-- 최근 수정순 목록(sort=updated)과 탭 동기화용 목록 조회
CREATE INDEX note_user_updated_idx ON note (user_id, updated_at DESC);

-- 주제 삭제 시 ON DELETE SET NULL이 미분류와 제목이 겹치면 note_topic_title_unique 위반으로 실패한다.
-- 겹치는 제목 목록(error.details.titles)을 돌려주려면 BE가 삭제 전에 아래처럼 먼저 검사한다.
--   SELECT n.title FROM note n
--   WHERE n.user_id = :userId AND n.topic_id = :topicId
--     AND EXISTS (SELECT 1 FROM note u
--                 WHERE u.user_id = :userId AND u.topic_id IS NULL AND u.title = n.title);

-- 검색(제목·본문 부분 일치, 대소문자 무시)은 초기에는 사용자 범위의 ILIKE 순차 검색으로 충분하다.
-- 노트가 많아지면 pg_trgm GIN 인덱스를 V2 이후에 추가한다.
--   CREATE EXTENSION IF NOT EXISTS pg_trgm;
--   CREATE INDEX note_title_trgm_idx ON note USING gin (title gin_trgm_ops);
--   CREATE INDEX note_body_trgm_idx  ON note USING gin (body gin_trgm_ops);

-- 분석·개념·관계·원문 구간·후보·검토
-- 상태 값은 PostgreSQL enum 대신 text + CHECK로 둔다. 관계 유형처럼 목록이 자주 바뀌어도 ALTER TYPE 없이 고칠 수 있다.
-- 개념은 병합(F-GRA-01)으로만 사라진다. 병합할 때 참조를 모두 옮기므로 개념 FK는 CASCADE 없이 둔다.
-- 사용자 범위(erd.md E6): concept, evidence_span, candidate에 user_id를 둔다. 나머지는 노트·개념·후보를 거쳐 사용자가 정해진다.
-- 관계 유형(relation_type)은 모든 사용자가 같이 쓰는 닫힌 목록이다.

-- 분석 작업 (F-ANL-01) ------------------------------------------------------

CREATE TABLE analysis_job (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    note_id       uuid        NOT NULL REFERENCES note (id) ON DELETE CASCADE,
    note_version  integer     NOT NULL,                   -- 분석한 노트의 version
    status        text        NOT NULL DEFAULT 'pending',
    extractor     text        NOT NULL,                   -- 추출 프롬프트·모델 버전 (예: extract-v0)
    attempt       integer     NOT NULL DEFAULT 0,
    error_message text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    CONSTRAINT analysis_job_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed'))
);

CREATE INDEX analysis_job_note_idx ON analysis_job (note_id, created_at DESC);
-- 한 노트에 대기·진행 중인 작업은 하나만
CREATE UNIQUE INDEX analysis_job_one_active ON analysis_job (note_id) WHERE status IN ('pending', 'running');

-- 개념 (그래프 노드, F-GRA-01) ---------------------------------------------

CREATE TABLE concept (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    label       text        NOT NULL,                     -- 대표 표기
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX concept_user_idx ON concept (user_id);

-- 노트에 나온 개념 표기. 노트별 개념 목록(F-GRA-03)과 병합 판단의 입력
CREATE TABLE concept_mention (
    id              uuid    PRIMARY KEY DEFAULT gen_random_uuid(),
    concept_id      uuid    NOT NULL REFERENCES concept (id),
    note_id         uuid    NOT NULL REFERENCES note (id) ON DELETE CASCADE,
    analysis_job_id uuid    REFERENCES analysis_job (id) ON DELETE SET NULL,
    surface         text    NOT NULL,                     -- 원문 표기
    start_offset    integer NOT NULL,                     -- NFC 마크다운 원문 기준, UTF-16 코드 단위 (erd.md E1)
    end_offset      integer NOT NULL,
    CONSTRAINT concept_mention_range CHECK (start_offset >= 0 AND end_offset > start_offset)
);

CREATE INDEX concept_mention_note_idx    ON concept_mention (note_id);
CREATE INDEX concept_mention_concept_idx ON concept_mention (concept_id);

-- 병합 이력. 합쳐져 사라진 개념은 행을 지우고 표기만 남긴다
CREATE TABLE concept_merge (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    from_concept_id uuid        NOT NULL,                 -- 지운 개념의 id (FK 없음)
    from_label      text        NOT NULL,
    into_concept_id uuid        NOT NULL REFERENCES concept (id),
    method          text        NOT NULL,
    similarity      real,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT concept_merge_method CHECK (method IN ('auto', 'llm', 'user'))
);

-- 관계 유형 (닫힌 목록, 10장 미정) ------------------------------------------

CREATE TABLE relation_type (
    code        text    PRIMARY KEY,                      -- 예: cause
    label       text    NOT NULL,                         -- 예: 원인
    description text,
    active      boolean NOT NULL DEFAULT true
);

-- 합성 규칙 (F-VER-03). 예: cause + cause → indirect_cause
CREATE TABLE relation_type_rule (
    first_type  text NOT NULL REFERENCES relation_type (code),
    second_type text NOT NULL REFERENCES relation_type (code),
    result_type text NOT NULL REFERENCES relation_type (code),
    PRIMARY KEY (first_type, second_type)
);

-- 원문 구간 (F-EXT-02) -------------------------------------------------------

CREATE TABLE evidence_span (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,  -- note_id가 NULL이 돼도 소유자를 안다
    note_id         uuid        REFERENCES note (id) ON DELETE SET NULL,  -- 노트가 지워져도 근거 소실 표시를 위해 남긴다
    analysis_job_id uuid        REFERENCES analysis_job (id) ON DELETE SET NULL,
    note_version    integer     NOT NULL,                  -- 구간을 계산한 노트 version
    start_offset    integer     NOT NULL,
    end_offset      integer     NOT NULL,
    quote           text        NOT NULL,                  -- 저장 당시 원문 문장 그대로 (LLM 인용 아님)
    status          text        NOT NULL DEFAULT 'valid',
    lost_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT evidence_span_range  CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT evidence_span_status CHECK (status IN ('valid', 'lost')),
    CONSTRAINT evidence_span_lost   CHECK ((status = 'lost') = (lost_at IS NOT NULL))
);

CREATE INDEX evidence_span_note_idx ON evidence_span (note_id);

-- 관계 (그래프 간선, F-GRA-02·F-REV-02·F-REV-04) ------------------------------
-- 추출 관계(extracted)와 확정 관계(confirmed)만 여기 둔다.
-- 후보·가설·기각은 candidate 테이블의 상태다.

CREATE TABLE relation (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    source_concept_id uuid        NOT NULL REFERENCES concept (id),
    target_concept_id uuid        NOT NULL REFERENCES concept (id),
    type_code         text        NOT NULL REFERENCES relation_type (code),
    origin            text        NOT NULL,
    status            text        NOT NULL DEFAULT 'active',
    as_hypothesis     boolean     NOT NULL DEFAULT false,  -- 가설로 승인한 확정 관계 (표시 방식 D16)
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT relation_origin     CHECK (origin IN ('extracted', 'confirmed')),
    CONSTRAINT relation_status     CHECK (status IN ('active', 'evidence_lost')),
    CONSTRAINT relation_not_self   CHECK (source_concept_id <> target_concept_id),
    CONSTRAINT relation_hypothesis CHECK (NOT as_hypothesis OR origin = 'confirmed'),
    -- 여러 노트에서 같은 관계가 나오면 한 행에 근거를 여러 개 붙인다
    CONSTRAINT relation_unique UNIQUE (source_concept_id, target_concept_id, type_code, origin)
);

CREATE INDEX relation_target_idx ON relation (target_concept_id);

CREATE TABLE relation_evidence (
    relation_id      uuid NOT NULL REFERENCES relation (id) ON DELETE CASCADE,
    evidence_span_id uuid NOT NULL REFERENCES evidence_span (id) ON DELETE CASCADE,
    PRIMARY KEY (relation_id, evidence_span_id)
);

CREATE INDEX relation_evidence_span_idx ON relation_evidence (evidence_span_id);

-- 후보 (F-PATH-01·F-EXP-01·F-VER-*·F-CAN-01) -----------------------------------

CREATE TABLE candidate (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    source_concept_id  uuid        NOT NULL REFERENCES concept (id),  -- A
    bridge_concept_id  uuid        NOT NULL REFERENCES concept (id),  -- B (매개 개념)
    target_concept_id  uuid        NOT NULL REFERENCES concept (id),  -- C
    kind               text        NOT NULL,              -- candidate: 모든 단계에 근거 / hypothesis: 빈 단계·합성 규칙 없음
    status             text        NOT NULL DEFAULT 'open',
    proposed_type_code text        REFERENCES relation_type (code),   -- 합성 규칙으로 나온 A→C 유형. 없으면 NULL
    summary            text        NOT NULL,              -- 한 줄 설명
    score              real,                              -- 순위 점수 (F-RANK-01)
    pipeline_version   text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT candidate_kind     CHECK (kind IN ('candidate', 'hypothesis')),
    -- withdrawn: 검토 전에 근거가 사라져 시스템이 거둔 후보 (erd.md E4)
    CONSTRAINT candidate_status   CHECK (status IN ('open', 'accepted', 'rejected', 'withdrawn')),
    CONSTRAINT candidate_distinct CHECK (source_concept_id <> target_concept_id
                                         AND bridge_concept_id <> source_concept_id
                                         AND bridge_concept_id <> target_concept_id),
    -- 같은 A-B-C는 한 번만 제안한다. 기각한 후보도 남아 있어 다시 제안되지 않는다 (F-REV-03)
    CONSTRAINT candidate_path_unique UNIQUE (source_concept_id, bridge_concept_id, target_concept_id)
);

CREATE INDEX candidate_open_idx ON candidate (user_id, score DESC) WHERE status = 'open';

-- 경로의 각 단계. relation_id가 NULL이면 원문 근거가 없는 빈 단계
CREATE TABLE candidate_step (
    candidate_id    uuid     NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    step_order      smallint NOT NULL,
    from_concept_id uuid     NOT NULL REFERENCES concept (id),
    to_concept_id   uuid     NOT NULL REFERENCES concept (id),
    type_code       text     REFERENCES relation_type (code),
    relation_id     uuid     REFERENCES relation (id) ON DELETE SET NULL,
    is_gap          boolean  NOT NULL,
    PRIMARY KEY (candidate_id, step_order),
    CONSTRAINT candidate_step_order CHECK (step_order >= 1)
);

CREATE INDEX candidate_step_relation_idx ON candidate_step (relation_id);

-- 설명의 주장. 각 주장은 근거 문장을 가리킨다 (F-EXP-01). 가정은 근거가 없을 수 있다
CREATE TABLE candidate_claim (
    id               uuid     PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id     uuid     NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    kind             text     NOT NULL,
    sort_order       smallint NOT NULL,
    body             text     NOT NULL,
    evidence_span_id uuid     REFERENCES evidence_span (id) ON DELETE SET NULL,
    CONSTRAINT candidate_claim_kind  CHECK (kind IN ('premise', 'condition', 'assumption', 'conclusion')),
    CONSTRAINT candidate_claim_order UNIQUE (candidate_id, sort_order)
);

-- 검증 층 1~3 결과 (F-VER-01~03). 평가(F-EVAL-03) 입력
CREATE TABLE verification_result (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id uuid        NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    layer        smallint    NOT NULL,
    step_order   smallint,                                -- 층 1은 단계별
    outcome      text        NOT NULL,
    detail       jsonb,
    model        text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT verification_layer   CHECK (layer IN (1, 2, 3)),
    CONSTRAINT verification_outcome CHECK (outcome IN ('pass', 'fail', 'uncertain'))
);

CREATE INDEX verification_candidate_idx ON verification_result (candidate_id);

-- 검토 이력 (F-REV-01·F-EVAL-04) ---------------------------------------------

CREATE TABLE review (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id  uuid        NOT NULL REFERENCES candidate (id) ON DELETE CASCADE,
    action        text        NOT NULL,
    as_hypothesis boolean     NOT NULL DEFAULT false,
    relation_id   uuid        REFERENCES relation (id) ON DELETE SET NULL,  -- 승인·수정으로 만든 확정 관계
    original      jsonb       NOT NULL,                   -- 검토 당시 후보 (유형·방향·설명)
    modified      jsonb,                                  -- 수정 내용 (modify만)
    reviewed_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT review_action   CHECK (action IN ('approve', 'modify', 'reject')),
    CONSTRAINT review_modified CHECK ((action = 'modify') = (modified IS NOT NULL)),
    -- 후보 하나에 검토는 한 번 (E5: 재검토 불가)
    CONSTRAINT review_one_per_candidate UNIQUE (candidate_id)
);

CREATE INDEX review_relation_idx ON review (relation_id);
