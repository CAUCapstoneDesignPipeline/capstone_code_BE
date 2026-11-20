-- 주제 하위 계층(#141). parent_id가 NULL이면 최상위 주제다. 깊이 제한은 두지 않는다.
-- V10은 추천 외부 지식(#137)이 쓰므로 이 파일은 V11이다.
ALTER TABLE topic ADD COLUMN parent_id uuid;

-- 부모는 같은 사용자의 주제만 가리킬 수 있다. 주제 삭제 시 하위 주제는 서비스가 먼저 부모로 옮기므로
-- 남은 자식이 있으면 삭제가 실패하도록 기본 동작(NO ACTION)을 쓴다.
ALTER TABLE topic ADD CONSTRAINT topic_parent_fk
    FOREIGN KEY (parent_id, user_id) REFERENCES topic (id, user_id);
ALTER TABLE topic ADD CONSTRAINT topic_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id);

-- 이름 중복 범위를 같은 부모 안으로 좁힌다. 최상위끼리도(parent_id NULL) 중복을 막는다.
-- 주제를 지울 때 하위 주제가 지워지는 주제와 같은 이름으로 한 단계 올라올 수 있어, 삭제 트랜잭션에서만
-- 검사를 커밋 시점으로 미룰 수 있게 DEFERRABLE로 둔다(평소에는 즉시 검사).
ALTER TABLE topic DROP CONSTRAINT topic_user_name_unique;
ALTER TABLE topic ADD CONSTRAINT topic_user_parent_name_unique UNIQUE NULLS NOT DISTINCT (user_id, parent_id, name)
    DEFERRABLE INITIALLY IMMEDIATE;

-- 같은 부모 안에서 sort_order로 정렬한다.
DROP INDEX topic_user_sort_idx;
CREATE INDEX topic_user_parent_sort_idx ON topic (user_id, parent_id, sort_order, name);
