package com.example.capstone.analysis.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import com.example.capstone.candidate.dto.request.ReviewEventRequest;

@Repository
public class AnalysisReviewRepository {
    private final AnalysisStore db;
    public AnalysisReviewRepository(AnalysisStore db) { this.db=db; }
    private static final String INSIGHT="""
        select jsonb_build_object('id',r.id,'revision',r.revision,'conclusion',r.conclusion,'conditions',r.conditions,'trace',r.trace,
          'derivationStatus',r.derivation_status,'soundness',r.soundness,'reviewStatus',r.review_status,'freshness',r.freshness,'noveltyNoteIds',r.novelty_note_ids,
          'evidence',coalesce((select jsonb_agg(distinct jsonb_build_object('noteId',s.original_note_id,'noteVersion',s.note_version,'inputRevision',s.input_revision,
            'bodySha256',s.body_sha256,'startOffset',e.start_offset,'endOffset',e.end_offset,'quote',e.quote))
            from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id and s.job_id=e.job_id
            where e.result_id=r.id and e.user_id=r.user_id),'[]'::jsonb))::text from analysis_result r
        """;
    public List<JsonNode> list(UUID user,boolean recommendations,int limit) {
        return db.rows(INSIGHT+"""
          where r.user_id=:user and (:history=true or (r.derivation_status='conclusion' and r.soundness='sound' and r.freshness='current'
           and r.review_status in ('unreviewed','accepted') and coalesce(r.detail->>'hypothesis','false')='false'
           and (select count(distinct s.original_note_id) from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
             where e.result_id=r.id and e.user_id=r.user_id and s.freshness='current')>=2))
          order by r.created_at desc,r.id limit :limit
          """,Map.of("user",user,"history",!recommendations,"limit",limit));
    }
    public JsonNode insight(UUID user,UUID id) { return db.one(INSIGHT+" where r.user_id=:user and r.id=:id",Map.of("user",user,"id",id)); }
    public JsonNode lock(UUID user,UUID id) { return db.one("select to_jsonb(r)::text from analysis_result r where r.user_id=:user and r.id=:id for update",Map.of("user",user,"id",id)); }
    public JsonNode replay(UUID user,String key) { return db.one("select to_jsonb(e)::text from analysis_review_event e where user_id=:user and idempotency_key=:key",Map.of("user",user,"key",key)); }
    public void review(UUID user,UUID id,int revision,ReviewEventRequest request,String hash) {
        var p=new LinkedHashMap<String,Object>();p.put("user",user);p.put("result",id);p.put("revision",revision);p.put("action",request.action());p.put("reason",request.reason());
        p.put("learning",request.learning());p.put("edited",request.editedConclusion());p.put("key",request.idempotencyKey());p.put("hash",hash);p.put("id",UUID.randomUUID());
        db.write("""
          insert into analysis_review_event(id,user_id,result_id,revision,action,reason,learning,edited_conclusion,idempotency_key,request_sha256)
          values(:id,:user,:result,:revision,:action,:reason,:learning,:edited,:key,:hash)
          """,p);
        db.write("""
          update analysis_result set revision=:revision,review_status=case :action when 'accept' then 'accepted' when 'reject' then 'rejected' else 'modified' end,
           soundness=case when :action='modify' then 'unchecked' else soundness end,
           conclusion=case when :action='modify' then :edited else conclusion end where user_id=:user and id=:result
          """,Map.of("revision",revision,"action",request.action(),"edited",request.editedConclusion()==null?"":request.editedConclusion(),"user",user,"result",id));
    }
    public List<JsonNode> reviews(UUID user) { return db.rows("select to_jsonb(e)::text from analysis_review_event e where user_id=:user order by created_at desc,id",Map.of("user",user)); }
}
