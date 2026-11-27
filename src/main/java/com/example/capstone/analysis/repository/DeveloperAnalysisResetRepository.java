package com.example.capstone.analysis.repository;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** 한 계정의 분석 작업과 거기서 나온 그래프 항목·추천·피드백·검토 이력을 모두 지운다. 노트와 주제는 건드리지 않는다. */
@Repository
public class DeveloperAnalysisResetRepository {
    private final AnalysisStore db;
    public DeveloperAnalysisResetRepository(AnalysisStore db) { this.db=db; }

    /** 대상 계정 행을 잠가 노트 저장·삭제와 순서를 맞춘다. 없는 계정이면 false. */
    public boolean lockUser(UUID user) {
        return db.one("select to_jsonb(id)::text from app_user where id=:user for update",Map.of("user",user))!=null;
    }

    public Counts reset(UUID user) {
        var p=Map.of("user",user);
        // 검토 이력은 추가 전용이다. 이 트랜잭션의 삭제만 허용한다(V12).
        db.one("select to_jsonb(set_config('capstone.analysis_reset','on',true))::text",Map.of());
        db.write("delete from analysis_review_event where user_id=:user",p);
        db.one("select to_jsonb(set_config('capstone.analysis_reset','off',true))::text",Map.of());
        int feedback=db.write("delete from recommendation_feedback where user_id=:user",p);
        db.write("delete from recommendation_external_knowledge where user_id=:user",p);
        // 외래 키가 서로 물려 있어 참조하는 쪽부터 지운다.
        db.write("delete from analysis_result_evidence where user_id=:user",p);
        db.write("delete from analysis_dependency where user_id=:user",p);
        int results=db.write("delete from analysis_result where user_id=:user",p);
        int graphItems=db.write("delete from analysis_graph_item where user_id=:user",p);
        db.write("delete from analysis_fact where user_id=:user",p);
        db.write("delete from analysis_stage where user_id=:user",p);
        db.write("delete from analysis_snapshot where user_id=:user",p);
        int jobs=db.write("delete from analysis_job where user_id=:user",p);
        db.write("update note set analysis_rerequested=false,analysis_supersede_streak=0 where user_id=:user and (analysis_rerequested or analysis_supersede_streak<>0)",p);
        db.write("update app_user set graph_revision=graph_revision+1 where id=:user",p);
        return new Counts(jobs,results,graphItems,feedback);
    }

    public record Counts(int jobs,int results,int graphItems,int feedback) {}
}
