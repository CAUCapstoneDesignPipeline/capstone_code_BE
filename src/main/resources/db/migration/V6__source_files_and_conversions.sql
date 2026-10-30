-- 원본은 opaque key로만 참조하며 삭제 의도와 실제 삭제 완료를 분리한다.
create table source_file (
 id uuid primary key, user_id uuid not null references app_user(id),
 object_key uuid not null unique, media_type text not null check(media_type in ('image/png','image/jpeg','application/pdf')),
 content_sha256 char(64) not null check(content_sha256 ~ '^[a-f0-9]{64}$'),
 byte_size bigint not null check(byte_size between 1 and 10485760), page_count int not null check(page_count between 1 and 20),
 created_at timestamptz not null default clock_timestamp(), expires_at timestamptz not null default clock_timestamp()+interval '7 days',
 deleted_at timestamptz, physical_deleted_at timestamptz, unique(user_id,id)
);
create table conversion_job (
 id uuid primary key,user_id uuid not null,file_id uuid not null,
 idempotency_key varchar(80) not null, status text not null check(status in ('queued','running','succeeded','failed','cancelled')),
 conversion_revision int not null default 1 check(conversion_revision>=1), result_revision int check(result_revision>=1),
 error_code text, result jsonb, engine_revision text not null,
 attempt int not null default 0 check(attempt between 0 and 2), attempt_id uuid, lease_token uuid, lease_until timestamptz,
 deadline timestamptz not null default clock_timestamp()+interval '5 minutes',
 created_at timestamptz not null default clock_timestamp(),finished_at timestamptz,
 unique(user_id,idempotency_key),unique(user_id,id),unique(user_id,id,file_id),
 foreign key(user_id,file_id) references source_file(user_id,id),
 check((result is null and result_revision is null) or (result is not null and result_revision is not null))
);
create unique index conversion_active_file on conversion_job(user_id,file_id) where status in ('queued','running');
create table conversion_confirmation (
 job_id uuid primary key,user_id uuid not null,file_id uuid not null,
 idempotency_key varchar(80) not null,request_sha256 char(64) not null,
 note_id uuid,original_note_id uuid not null,
 result_revision int not null check(result_revision>=1),note_version int not null check(note_version>=0),
 mapping_status text not null check(mapping_status in ('verified','unverified','invalidated')),
 source_sha256 char(64) not null,markdown_sha256 char(64) not null,mappings jsonb not null,
 created_at timestamptz not null default clock_timestamp(),
 foreign key(user_id,note_id) references note(user_id,id) on delete set null(note_id),
 foreign key(user_id,job_id,file_id) references conversion_job(user_id,id,file_id),
 foreign key(user_id,file_id) references source_file(user_id,id),
 unique(user_id,idempotency_key)
);

create table conversion_submission (
 user_id uuid not null,idempotency_key varchar(80) not null,file_id uuid not null,job_id uuid not null,
 primary key(user_id,idempotency_key),
 foreign key(user_id,job_id,file_id) references conversion_job(user_id,id,file_id)
);

create index conversion_job_queue on conversion_job(created_at,id) where status in ('queued','running');
create index source_file_cleanup on source_file(expires_at,id) where deleted_at is null;
