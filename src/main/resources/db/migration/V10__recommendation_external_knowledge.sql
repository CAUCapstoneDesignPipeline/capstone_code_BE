-- 추천 카드의 외부 지식(요약·출처 URL). 분석 결과 하나에 AI를 한 번만 부르고 그 결과를 남긴다.
-- pending·running은 호출 대기·진행, found·none·failed는 끝난 상태다. 결과가 지워지면 함께 지워진다.
create table recommendation_external_knowledge (
 user_id uuid not null, result_id uuid not null,
 status text not null check(status in ('pending','running','found','none','failed')),
 attempts integer not null default 0 check(attempts>=0),
 summary text check(char_length(summary) between 1 and 300),
 source_url text check(char_length(source_url) between 1 and 2048),
 lease_token uuid, claimed_at timestamptz,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 primary key(user_id,result_id),
 foreign key(user_id,result_id) references analysis_result(user_id,id) on delete cascade,
 check((status='found')=(summary is not null and source_url is not null))
);
create index recommendation_external_knowledge_queue on recommendation_external_knowledge(created_at) where status in ('pending','running');
