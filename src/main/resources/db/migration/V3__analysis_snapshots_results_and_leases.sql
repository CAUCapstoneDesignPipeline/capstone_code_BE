-- Immutable input snapshots and normalized ownership edges. V1/V2 remain untouched.
ALTER TABLE note ADD COLUMN input_revision bigint NOT NULL DEFAULT 0 CHECK (input_revision BETWEEN 0 AND 9007199254740991);
ALTER TABLE note ADD CONSTRAINT note_owner_id UNIQUE(user_id,id);
ALTER TABLE app_user ADD COLUMN graph_revision bigint NOT NULL DEFAULT 0 CHECK (graph_revision BETWEEN 0 AND 9007199254740991);
ALTER TABLE analysis_job ADD COLUMN user_id uuid REFERENCES app_user(id);
ALTER TABLE analysis_job ADD COLUMN lifecycle_state text CHECK(lifecycle_state IN ('queued','running','succeeded','failed','cancelled','superseded'));
ALTER TABLE analysis_job ADD COLUMN input_revision bigint;
ALTER TABLE analysis_job ADD COLUMN graph_revision bigint;
ALTER TABLE analysis_job ADD COLUMN lease_token uuid;
ALTER TABLE analysis_job ADD COLUMN lease_until timestamptz;
ALTER TABLE analysis_job ADD COLUMN deadline timestamptz;
ALTER TABLE analysis_job ADD COLUMN failure_code text;
ALTER TABLE analysis_job ADD COLUMN contract_revision text;
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_owner_id UNIQUE(user_id,id);
ALTER TABLE analysis_job ADD CONSTRAINT analysis_job_note_owner FOREIGN KEY(user_id,note_id) REFERENCES note(user_id,id) ON DELETE CASCADE;
CREATE INDEX analysis_job_claim ON analysis_job(lifecycle_state,lease_until,created_at) WHERE lifecycle_state IN ('queued','running');

CREATE TABLE analysis_snapshot (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, job_id uuid NOT NULL, note_id uuid, original_note_id uuid NOT NULL,
 note_version integer NOT NULL CHECK(note_version>=0), input_revision bigint NOT NULL CHECK(input_revision>=0),
 topic_id uuid, title text NOT NULL, body text NOT NULL CHECK(char_length(body)<=50000),
 body_sha256 text NOT NULL CHECK(body_sha256 ~ '^[0-9a-f]{64}$'),
 freshness text NOT NULL DEFAULT 'current' CHECK(freshness IN ('current','stale','evidence_lost')),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,id), UNIQUE(job_id,note_id),
 FOREIGN KEY(user_id,job_id) REFERENCES analysis_job(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,note_id) REFERENCES note(user_id,id) ON DELETE SET NULL (note_id)
);
CREATE INDEX analysis_snapshot_note ON analysis_snapshot(user_id,note_id,input_revision);
CREATE TABLE analysis_stage (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, job_id uuid NOT NULL, stage_key text NOT NULL,
 operation text NOT NULL, request_sha256 text NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 response jsonb NOT NULL, attempts integer NOT NULL CHECK(attempts BETWEEN 1 AND 3), duration_ms bigint NOT NULL CHECK(duration_ms>=0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(job_id,stage_key),
 FOREIGN KEY(user_id,job_id) REFERENCES analysis_job(user_id,id) ON DELETE CASCADE
);
CREATE TABLE analysis_fact (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, job_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 external_ref text NOT NULL CHECK(char_length(external_ref) BETWEEN 1 AND 80),
 kind text NOT NULL, subject_key text NOT NULL, profile_version text NOT NULL,
 value jsonb NOT NULL, provenance jsonb NOT NULL,
 start_offset integer NOT NULL CHECK(start_offset>=0), end_offset integer NOT NULL CHECK(end_offset>start_offset), quote text NOT NULL,
 UNIQUE(user_id,id), UNIQUE(job_id,external_ref),
 FOREIGN KEY(user_id,job_id) REFERENCES analysis_job(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,snapshot_id) REFERENCES analysis_snapshot(user_id,id)
);
CREATE INDEX analysis_fact_snapshot ON analysis_fact(user_id,snapshot_id,profile_version);
CREATE TABLE analysis_result (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, job_id uuid NOT NULL,
 external_ref text NOT NULL, identity_sha256 text NOT NULL CHECK(identity_sha256 ~ '^[0-9a-f]{64}$'),
 revision integer NOT NULL DEFAULT 1 CHECK(revision>=1), kind text NOT NULL CHECK(kind IN ('graph','derivation')),
 conclusion text NOT NULL, conditions jsonb NOT NULL, trace jsonb NOT NULL, detail jsonb NOT NULL,
 derivation_status text NOT NULL CHECK(derivation_status IN ('conclusion','held','rejected','known')),
 soundness text NOT NULL CHECK(soundness IN ('sound','unsound','uncertain','unchecked')),
 review_status text NOT NULL DEFAULT 'unreviewed' CHECK(review_status IN ('unreviewed','accepted','rejected','modified')),
 freshness text NOT NULL DEFAULT 'current' CHECK(freshness IN ('current','stale','evidence_lost')),
 novelty_note_ids jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,id), UNIQUE(job_id,external_ref), UNIQUE(job_id,identity_sha256),
 FOREIGN KEY(user_id,job_id) REFERENCES analysis_job(user_id,id) ON DELETE CASCADE
);
CREATE INDEX analysis_result_recommendation ON analysis_result(user_id,freshness,soundness,derivation_status,created_at DESC);
CREATE TABLE analysis_result_evidence (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, result_id uuid NOT NULL, snapshot_id uuid NOT NULL, fact_id uuid,
 claim_ref text NOT NULL, start_offset integer NOT NULL CHECK(start_offset>=0),
 end_offset integer NOT NULL CHECK(end_offset>start_offset), quote text NOT NULL,
 UNIQUE(result_id,claim_ref,snapshot_id,start_offset,end_offset),
 FOREIGN KEY(user_id,result_id) REFERENCES analysis_result(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,snapshot_id) REFERENCES analysis_snapshot(user_id,id),
 FOREIGN KEY(user_id,fact_id) REFERENCES analysis_fact(user_id,id)
);
CREATE TABLE analysis_dependency (
 user_id uuid NOT NULL, result_id uuid NOT NULL, depends_on_id uuid NOT NULL,
 PRIMARY KEY(result_id,depends_on_id), CHECK(result_id<>depends_on_id),
 FOREIGN KEY(user_id,result_id) REFERENCES analysis_result(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,depends_on_id) REFERENCES analysis_result(user_id,id)
);
CREATE TABLE analysis_review_event (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, result_id uuid NOT NULL, revision integer NOT NULL,
 action text NOT NULL CHECK(action IN ('accept','reject','modify')), reason text, learning text, edited_conclusion text,
 idempotency_key text NOT NULL CHECK(char_length(idempotency_key) BETWEEN 1 AND 80), request_sha256 text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(result_id,revision), UNIQUE(user_id,idempotency_key),
 FOREIGN KEY(user_id,result_id) REFERENCES analysis_result(user_id,id)
);
-- Events are append-only even if an application bug attempts an UPDATE/DELETE.
CREATE FUNCTION reject_analysis_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'analysis review history is append only'; END; $$;
CREATE TRIGGER analysis_review_append_only BEFORE UPDATE OR DELETE ON analysis_review_event
 FOR EACH ROW EXECUTE FUNCTION reject_analysis_review_mutation();
CREATE TABLE analysis_graph_item (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, job_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('concept','relation','evidence')), external_ref text NOT NULL,
 value jsonb NOT NULL, UNIQUE(user_id,id), UNIQUE(job_id,snapshot_id,kind,external_ref),
 FOREIGN KEY(user_id,job_id) REFERENCES analysis_job(user_id,id) ON DELETE CASCADE,
 FOREIGN KEY(user_id,snapshot_id) REFERENCES analysis_snapshot(user_id,id)
);
