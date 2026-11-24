package com.example.capstone.analysis.repository;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/**
 * 추천 카드 외부 지식의 호출 대기열과 결과. 분석 결과 하나에 한 행이며, 같은 지문·같은 내용의 결과가 이미 끝난 답을 받았으면 그 답을 복사해
 * AI를 다시 부르지 않는다. 호출 중인 행은 lease_token으로 소유를 확인하고, 오래 멈춘 행은 다른 worker가 다시 가져간다.
 */
@Repository
public class ExternalKnowledgeRepository {
    private final AnalysisStore db;
    public ExternalKnowledgeRepository(AnalysisStore db) { this.db=db; }
    public void enqueue(UUID user,UUID result) {
        db.write("""
          insert into recommendation_external_knowledge(user_id,result_id,status,summary,source_url)
          select r.user_id,r.id,coalesce(prev.status,'pending'),prev.summary,prev.source_url
          from analysis_result r
          left join lateral(select k.status,k.summary,k.source_url from recommendation_external_knowledge k
            join analysis_result o on o.id=k.result_id and o.user_id=k.user_id
           where k.user_id=r.user_id and o.recommendation_id=r.recommendation_id and o.content_sha256=r.content_sha256 and k.status in ('found','none')
           order by k.updated_at desc limit 1) prev on true
          where r.user_id=:user and r.id=:result
          on conflict do nothing
          """,Map.of("user",user,"result",result));
    }
    /** 대기 중이거나 5분 넘게 멈춘 행 하나를 가져간다. 호출에 쓸 결론·근거 문장·경로 개념 이름과 작업 context를 함께 준다. */
    public JsonNode claim(UUID token) {
        return db.one("""
          with candidate as(select user_id,result_id from recommendation_external_knowledge
            where status='pending' or (status='running' and claimed_at<clock_timestamp()-interval '5 minutes')
            order by created_at,result_id for update skip locked limit 1),
          claimed as(update recommendation_external_knowledge k set status='running',attempts=k.attempts+1,lease_token=:token,
            claimed_at=clock_timestamp(),updated_at=clock_timestamp()
            from candidate c where k.user_id=c.user_id and k.result_id=c.result_id returning k.user_id,k.result_id,k.attempts)
          select jsonb_build_object('userId',c.user_id,'resultId',c.result_id,'attempts',c.attempts,'jobId',j.id,'noteId',j.note_id,
            'noteVersion',j.note_version,'inputRevision',coalesce(j.input_revision,0),'graphRevision',coalesce(j.graph_revision,0),'conclusion',r.conclusion,
            'keywords',coalesce((select jsonb_agg(p.value->>'label' order by p.ordinality) from jsonb_array_elements(case when jsonb_typeof(r.detail->'path'->'concepts')='array' then r.detail->'path'->'concepts' else '[]'::jsonb end) with ordinality p),'[]'::jsonb),
            'evidence',coalesce((select jsonb_agg(x.quote order by x.requested desc,x.note) from(
              select distinct on(s.original_note_id) s.original_note_id note,s.original_note_id=j.note_id requested,e.quote
              from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
              where e.result_id=r.id and e.user_id=r.user_id and (r.kind<>'derivation' or e.claim_ref='premise')
              order by s.original_note_id,e.start_offset,e.end_offset,e.id) x),'[]'::jsonb))::text
          from claimed c join analysis_result r on r.id=c.result_id and r.user_id=c.user_id join analysis_job j on j.id=r.job_id and j.user_id=r.user_id
          """,Map.of("token",token));
    }
    /** 가져간 worker만 끝난 답을 기록한다. 출처가 있으면 found, 없으면 none이다. */
    public boolean found(UUID user,UUID result,UUID token,String summary,String sourceUrl) {
        return db.write("""
          update recommendation_external_knowledge set status='found',summary=:summary,source_url=:url,lease_token=null,updated_at=clock_timestamp()
          where user_id=:user and result_id=:result and status='running' and lease_token=:token
          """,Map.of("user",user,"result",result,"token",token,"summary",summary,"url",sourceUrl))==1;
    }
    public boolean none(UUID user,UUID result,UUID token) {
        return db.write("""
          update recommendation_external_knowledge set status='none',lease_token=null,updated_at=clock_timestamp()
          where user_id=:user and result_id=:result and status='running' and lease_token=:token
          """,Map.of("user",user,"result",result,"token",token))==1;
    }
    /** 일시적인 실패는 시도 횟수가 남았으면 다시 대기열에 넣고, 아니면 failed로 끝낸다. */
    public void release(UUID user,UUID result,UUID token,boolean retry,int maxAttempts) {
        db.write("""
          update recommendation_external_knowledge set status=case when :retry and attempts<:max then 'pending' else 'failed' end,lease_token=null,updated_at=clock_timestamp()
          where user_id=:user and result_id=:result and status='running' and lease_token=:token
          """,Map.of("user",user,"result",result,"token",token,"retry",retry,"max",maxAttempts));
    }
}
