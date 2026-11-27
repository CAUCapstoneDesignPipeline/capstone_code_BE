-- 개발자용 분석 결과 초기화(#158)가 검토 이력을 지울 수 있게 한다.
-- 같은 트랜잭션에서 capstone.analysis_reset 을 on 으로 둔 DELETE만 통과한다. UPDATE와 그 밖의 DELETE는 계속 막는다.
CREATE OR REPLACE FUNCTION reject_analysis_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND current_setting('capstone.analysis_reset',true)='on' THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'analysis review history is append only';
END; $$;
