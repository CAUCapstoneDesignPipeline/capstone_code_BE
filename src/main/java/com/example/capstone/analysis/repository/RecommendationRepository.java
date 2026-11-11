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
    public List<JsonNode> cards(UUID user,UUID job,UUID note,int limit) {
        return db.rows("select jsonb_build_object('id',r.id,'conclusion',r.conclusion,'evidenceA',"+EVIDENCE.formatted("s.original_note_id=:note")
                +",'evidenceB',"+EVIDENCE.formatted("s.original_note_id<>:note")+"""
          ,'relatedKeywords',coalesce((select jsonb_agg(c.value->>'label' order by c.ordinality) from jsonb_array_elements(case when jsonb_typeof(r.detail->'path'->'concepts')='array' then r.detail->'path'->'concepts' else '[]'::jsonb end) with ordinality c),'[]'::jsonb))::text
          from analysis_result r
          where r.user_id=:user and r.job_id=:job and r.derivation_status='conclusion' and r.soundness='sound' and r.freshness='current'
           and coalesce(r.detail->>'hypothesis','false')='false' and r.review_status<>'rejected'
           and exists(select 1 from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
            where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise') and s.original_note_id=:note)
           and exists(select 1 from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
            where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise') and s.original_note_id<>:note)
           and not exists(select 1 from recommendation_feedback f where f.user_id=r.user_id and f.result_id=r.id and f.action in ('understood','known'))
          order by r.created_at,r.id limit :limit
          """,Map.of("user",user,"job",job,"note",note,"limit",limit));
    }
    public JsonNode lockResult(UUID user,UUID id) { return db.one("select to_jsonb(r)::text from analysis_result r where r.user_id=:user and r.id=:id for update",Map.of("user",user,"id",id)); }
    /** 이해함·알고 있던 내용은 최종 답이다. 이해가 안 됨은 뒤에 최종 답을 받을 수 있다. */
    public String finalAnswer(UUID user,UUID result) {
        JsonNode row=db.one("select to_jsonb(action)::text from recommendation_feedback where user_id=:user and result_id=:result and action in ('understood','known') order by created_at,id limit 1",Map.of("user",user,"result",result));
        return row==null?null:row.asString();
    }
    public void feedback(UUID user,UUID result,String action) {
        db.write("insert into recommendation_feedback(id,user_id,result_id,action) values(:id,:user,:result,:action)",Map.of("id",UUID.randomUUID(),"user",user,"result",result,"action",action));
    }
}
