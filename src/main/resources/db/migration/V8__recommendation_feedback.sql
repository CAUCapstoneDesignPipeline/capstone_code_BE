-- 추천 카드 피드백(이해함·알고 있던 내용·이해가 안 됨). 검토 이벤트와 별도로 화면 응답 이력을 남긴다.
create table recommendation_feedback (
 id uuid primary key, user_id uuid not null, result_id uuid not null,
 action text not null check(action in ('understood','known','confused')),
 created_at timestamptz not null default now(),
 foreign key(user_id,result_id) references analysis_result(user_id,id) on delete cascade
);
create index recommendation_feedback_result on recommendation_feedback(user_id,result_id,created_at);
