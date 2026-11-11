package com.example.capstone.analysis.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/** 분석 결과를 추천 카드로 투영한다. 카드의 한쪽 근거는 항상 작업을 요청한 노트다. */
@Repository
public class RecommendationRepository {
    private static final String JOB="""
        select jsonb_build_object('id',j.id,'noteId',j.note_id,'status',j.status,'lifecycleState',j.lifecycle_state,'createdAt',j.created_at,'finishedAt',j.finished_at)::text
        from analysis_job j
        """;
    private static final String EVIDENCE="""
        (select jsonb_build_object('noteId',s.original_note_id,'noteTitle',coalesce(n.title,s.title),'noteVersion',s.note_version,
          'startOffset',e.start_offset,'endOffset',e.end_offset,'quote',e.quote)
         from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
          left join note n on n.id=s.note_id and n.user_id=s.user_id
         where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise') and %s
         order by s.original_note_id,e.start_offset,e.end_offset,e.id limit 1)
        """;
    private static final String ELIGIBLE="r.derivation_status='conclusion' and r.soundness='sound' and coalesce(r.detail->>'hypothesis','false')='false' and r.review_status<>'rejected'";
    private final AnalysisStore db;
    public RecommendationRepository(AnalysisStore db) { this.db=db; }
    public JsonNode job(UUID user,UUID job) { return db.one(JOB+" where j.id=:job and j.user_id=:user",Map.of("user",user,"job",job)); }
    /** 같은 노트에서 이 작업 뒤에 만들어진 가장 새 작업. 대체된 작업의 조회는 이 작업을 따른다. */
    public JsonNode newestAfter(UUID user,UUID job) {
        return db.one(JOB+"""
           join analysis_job base on base.id=:job and base.user_id=:user and base.note_id=j.note_id
          where j.user_id=:user and (j.created_at,j.id)>(base.created_at,base.id) order by j.created_at desc,j.id desc limit 1
          """,Map.of("user",user,"job",job));
    }
    /**
     * 작업이 낸 추천 후보마다 같은 지문(recommendation_id)의 가장 새 결과를 카드로 보여 준다.
     * 최종 답(이해함·알고 있던 내용)을 받은 지문은 다시 추천하지 않는다.
     */
    public List<JsonNode> cards(UUID user,UUID job,UUID note,int limit) {
        return db.rows("with candidates as(select distinct r.recommendation_id from analysis_result r where r.user_id=:user and r.job_id=:job and "+ELIGIBLE+"""
           and exists(select 1 from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
            where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise') and s.original_note_id=:note)
           and exists(select 1 from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
            where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise') and s.original_note_id<>:note)),
          latest as(select distinct on(r.recommendation_id) r.* from analysis_result r join candidates c on c.recommendation_id=r.recommendation_id
           where r.user_id=:user"""+" and "+ELIGIBLE+" order by r.recommendation_id,r.created_at desc,r.id desc)"+"""
          select jsonb_build_object('id',r.recommendation_id,'revision',r.recommendation_revision,'freshness',r.freshness,'conclusion',r.conclusion,'evidenceA',"""+EVIDENCE.formatted("s.original_note_id=:note")
                +",'evidenceB',"+EVIDENCE.formatted("s.original_note_id<>:note")+"""
          ,'relatedKeywords',coalesce((select jsonb_agg(c.value->>'label' order by c.ordinality) from jsonb_array_elements(case when jsonb_typeof(r.detail->'path'->'concepts')='array' then r.detail->'path'->'concepts' else '[]'::jsonb end) with ordinality c),'[]'::jsonb))::text
          from latest r
          where not exists(select 1 from recommendation_feedback f join analysis_result answered on answered.id=f.result_id and answered.user_id=f.user_id
            where f.user_id=r.user_id and answered.recommendation_id=r.recommendation_id and f.action in ('understood','known'))
          order by r.created_at,r.id limit :limit
          """,Map.of("user",user,"job",job,"note",note,"limit",limit));
    }
    /** 추천 id(지문)의 가장 새 결과를 잠근다. 피드백은 이 결과에 기록한다. */
    public JsonNode lockResult(UUID user,UUID id) {
        return db.one("select to_jsonb(r)::text from analysis_result r where r.user_id=:user and r.recommendation_id=:id and "+ELIGIBLE+" order by r.created_at desc,r.id desc limit 1 for update",Map.of("user",user,"id",id));
    }
    /** 이해함·알고 있던 내용은 최종 답이다. 이해가 안 됨은 뒤에 최종 답을 받을 수 있다. */
    public String finalAnswer(UUID user,UUID recommendation) {
        JsonNode row=db.one("""
          select to_jsonb(f.action)::text from recommendation_feedback f join analysis_result r on r.id=f.result_id and r.user_id=f.user_id
          where f.user_id=:user and r.recommendation_id=:recommendation and f.action in ('understood','known') order by f.created_at,f.id limit 1
          """,Map.of("user",user,"recommendation",recommendation));
        return row==null?null:row.asString();
    }
    public void feedback(UUID user,UUID result,String action) {
        db.write("insert into recommendation_feedback(id,user_id,result_id,action) values(:id,:user,:result,:action)",Map.of("id",UUID.randomUUID(),"user",user,"result",result,"action",action));
    }
}
