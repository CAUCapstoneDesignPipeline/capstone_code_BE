package com.example.capstone.analysis.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/**
 * 개발자 대시보드용 분석 작업 기록 조회. 모든 사용자의 작업을 본다.
 * 노트 제목·본문, 근거 문장, 결론, AI 요청·응답 원문, error_message는 투영하지 않는다.
 */
@Repository
public class DeveloperAnalysisJobRepository {
    private static final String SUMMARY = """
        'id',j.id,'user',jsonb_build_object('id',u.id,'email',u.email),'noteId',j.note_id,'noteVersion',j.note_version,
        'status',j.status,'lifecycleState',j.lifecycle_state,'failureCode',j.failure_code,'attempt',j.attempt,
        'createdAt',j.created_at,'startedAt',j.started_at,'finishedAt',j.finished_at,
        'durationMs',case when j.started_at is not null and j.finished_at is not null
          then (extract(epoch from j.finished_at-j.started_at)*1000)::bigint end
        """;
    private static final String FROM = """
        from analysis_job j join note n on n.id=j.note_id join app_user u on u.id=n.user_id
        """;
    /** RecommendationRepository의 추천 카드 조건과 같다. */
    private static final String RECOMMENDABLE = "r.derivation_status='conclusion' and r.soundness='sound' and coalesce(r.detail->>'hypothesis','false')='false' and r.review_status<>'rejected'";

    private final AnalysisStore db;

    public DeveloperAnalysisJobRepository(AnalysisStore db) {
        this.db = db;
    }

    public List<JsonNode> list(String status, Instant from, Instant to, int limit) {
        var sql = new StringBuilder("select jsonb_build_object(").append(SUMMARY).append(")::text ").append(FROM).append(" where true");
        var parameters = new LinkedHashMap<String, Object>();
        if (status != null) {
            sql.append(" and j.status=:status");
            parameters.put("status", status);
        }
        if (from != null) {
            sql.append(" and j.created_at>=:from");
            parameters.put("from", from);
        }
        if (to != null) {
            sql.append(" and j.created_at<:to");
            parameters.put("to", to);
        }
        sql.append(" order by j.created_at desc,j.id desc limit :limit");
        parameters.put("limit", limit);
        return db.rows(sql.toString(), parameters);
    }

    public JsonNode job(UUID job) {
        return db.one("select jsonb_build_object(" + SUMMARY + ",'extractor',j.extractor,'deadline',j.deadline)::text " + FROM + " where j.id=:job",
                Map.of("job", job));
    }

    /** 응답 원문 대신 완료 여부만 준다. 호출이 끝나지 않은 단계는 응답 자리에 _pending 표시가 남는다. */
    public List<JsonNode> stages(UUID job) {
        return db.rows("""
            select jsonb_build_object('stageKey',st.stage_key,'operation',st.operation,'attempts',st.attempts,'durationMs',st.duration_ms,
             'completed',not jsonb_exists(st.response,'_pending'),'createdAt',st.created_at)::text
            from analysis_stage st where st.job_id=:job order by st.created_at,st.id
            """, Map.of("job", job));
    }

    /**
     * 입력 노트별 사실 추출 캐시 사용 여부. 캐시를 쓰면 이 작업에 FACTS 단계 행이 생기지 않으므로
     * 저장된 사실의 provenance.cacheHit과 이 작업의 FACTS 응답으로 판단하고, 둘 다 없으면 null이다.
     */
    public List<JsonNode> inputs(UUID job) {
        return db.rows("""
            select jsonb_build_object('noteId',s.original_note_id,'factsCacheHit',
             case when exists(select 1 from analysis_fact f where f.user_id=s.user_id and f.snapshot_id=s.id and f.provenance->>'cacheHit'='true') then true
              when exists(select 1 from analysis_fact f where f.user_id=s.user_id and f.snapshot_id=s.id and f.provenance->>'cacheHit'='false')
               or exists(select 1 from analysis_stage st where st.user_id=s.user_id and st.job_id=s.job_id and st.operation='FACTS'
                and st.response->'context'->>'noteId'=s.original_note_id::text) then false end)::text
            from analysis_snapshot s where s.job_id=:job order by s.original_note_id
            """, Map.of("job", job));
    }

    public JsonNode results(UUID job) {
        return db.one("""
            select jsonb_build_object('total',(select count(*) from analysis_result r where r.job_id=:job),
             'recommendable',(select count(*) from analysis_result r where r.job_id=:job and %s),
             'feedback',(select jsonb_build_object('understood',count(*) filter(where f.action='understood'),
               'known',count(*) filter(where f.action='known'),'confused',count(*) filter(where f.action='confused'))
              from recommendation_feedback f join analysis_result r on r.id=f.result_id and r.user_id=f.user_id where r.job_id=:job))::text
            """.formatted(RECOMMENDABLE), Map.of("job", job));
    }
}
