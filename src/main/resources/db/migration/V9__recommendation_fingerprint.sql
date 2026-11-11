-- 추천 카드의 공개 id는 후보 지문(사용자·근거 노트 쌍·경로 개념 이름)으로 정한다.
-- 같은 지문이 다시 나오면 id를 유지하고, 결론·근거가 바뀌었을 때만 recommendation_revision을 올린다.
alter table analysis_result add column fingerprint_sha256 text;
alter table analysis_result add column recommendation_id uuid;
alter table analysis_result add column recommendation_revision integer;
alter table analysis_result add column content_sha256 text;
update analysis_result set fingerprint_sha256=identity_sha256,recommendation_id=id,recommendation_revision=1,content_sha256=identity_sha256;
alter table analysis_result alter column fingerprint_sha256 set not null;
alter table analysis_result alter column recommendation_id set not null;
alter table analysis_result alter column recommendation_revision set not null;
alter table analysis_result alter column content_sha256 set not null;
alter table analysis_result add constraint analysis_result_fingerprint_hex check(fingerprint_sha256 ~ '^[0-9a-f]{64}$');
alter table analysis_result add constraint analysis_result_content_hex check(content_sha256 ~ '^[0-9a-f]{64}$');
alter table analysis_result add constraint analysis_result_recommendation_revision check(recommendation_revision>=1);
create index analysis_result_recommendation_id on analysis_result(user_id,recommendation_id,created_at desc,id desc);
