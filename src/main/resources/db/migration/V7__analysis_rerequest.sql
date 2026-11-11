-- 사용자 변경으로 대체된 명시 분석 요청을 최신 내용으로 한 번 이어 실행하기 위한 표시와 연속 대체 횟수.
alter table note add column analysis_rerequested boolean not null default false;
alter table note add column analysis_supersede_streak integer not null default 0 check(analysis_supersede_streak>=0);
create index note_analysis_rerequested on note(updated_at,id) where analysis_rerequested;
