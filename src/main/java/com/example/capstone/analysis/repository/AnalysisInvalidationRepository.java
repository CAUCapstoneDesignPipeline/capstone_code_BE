package com.example.capstone.analysis.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisInvalidationRepository {
    private final AnalysisStore db;
    public AnalysisInvalidationRepository(AnalysisStore db) { this.db=db; }
    public void policyChanged(UUID user) {
        List<UUID> ids=db.rows("select to_jsonb(id)::text from note where user_id=:user order by id",Map.of("user",user)).stream().map(n->UUID.fromString(n.asString())).toList();
        invalidate(user,ids,true);
        db.write("update analysis_result set freshness='stale' where user_id=:user and freshness='current'",Map.of("user",user));
    }
    /** 인용한 근거 문장이 현재 본문에 글자 그대로 없거나 노트가 삭제되면 evidence_lost. 문장을 되돌려도 복원하지 않는다. */
    public void evidenceChanged(UUID user,UUID note,boolean deleted) {
        db.write("""
          update analysis_result set freshness='evidence_lost' where user_id=:user and freshness<>'evidence_lost' and id in(
           select e.result_id from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
            left join note n on n.id=s.original_note_id and n.user_id=s.user_id
           where e.user_id=:user and s.original_note_id=:note and (:deleted=true or n.id is null or strpos(n.body,e.quote)=0))
          """,Map.of("user",user,"note",note,"deleted",deleted));
        propagate(user);
    }
    /** 같은 요청 노트의 더 새 분석이 성공하면 이전 작업의 결과는 stale이다. */
    public void supersededByNewerAnalysis(UUID user,UUID note,UUID job) {
        db.write("""
          update analysis_result r set freshness='stale' where r.user_id=:user and r.freshness='current' and r.job_id in(
           select j.id from analysis_job j where j.user_id=:user and j.note_id=:note and j.id<>:job
            and (j.created_at,j.id)<(select newer.created_at,newer.id from analysis_job newer where newer.id=:job and newer.user_id=:user))
          """,Map.of("user",user,"note",note,"job",job));
        propagate(user);
    }
    // 근거를 잃었거나 낡은 결과에 기대는 도출 결과는 자기 문장이 남아 있어도 stale이다.
    private void propagate(UUID user) {
        db.write("""
          with recursive affected(id) as (
           select id from analysis_result where user_id=:user and freshness<>'current'
           union select d.result_id from analysis_dependency d join affected a on a.id=d.depends_on_id where d.user_id=:user)
          update analysis_result set freshness='stale' where user_id=:user and freshness='current' and id in(select id from affected)
          """,Map.of("user",user));
    }
    // 그래프 revision은 결과 게시에서만 올린다. snapshot에 이 노트가 없는 실행 중 작업은 계속 진행한다.
    public void invalidate(UUID user,List<UUID> ids,boolean inputChanged) {
        if(ids.isEmpty()) return;
        var p=Map.of("user",user,"ids",ids);
        if(inputChanged) db.write("update note set input_revision=input_revision+1 where user_id=:user and id in (:ids)",p);
        db.write("update analysis_snapshot set freshness='stale' where user_id=:user and original_note_id in (:ids) and freshness='current'",p);
        // 결과의 freshness는 근거 문장 기준(evidenceChanged)과 새 분석 성공(supersededByNewerAnalysis)으로만 바뀐다.
        db.write("""
          update analysis_job j set status='failed',lifecycle_state='superseded',failure_code='STALE_INPUT',finished_at=clock_timestamp(),lease_token=null,lease_until=null
           where j.user_id=:user and j.lifecycle_state in ('queued','running') and exists(select 1 from analysis_snapshot s where s.job_id=j.id and s.user_id=:user and s.original_note_id in (:ids))
          """,p);
        db.write("update evidence_span set status='lost',lost_at=clock_timestamp() where user_id=:user and note_id in (:ids) and status='valid'",p);
        db.write("""
          with recursive lost(id) as (
           select r.id from relation r join concept c on c.id=r.source_concept_id and c.user_id=:user
            where exists(select 1 from relation_evidence re join evidence_span e on e.id=re.evidence_span_id and e.user_id=:user where re.relation_id=r.id and e.note_id in (:ids))
           union select rv.relation_id from candidate_step cs join lost l on l.id=cs.relation_id
            join candidate c on c.id=cs.candidate_id and c.user_id=:user join review rv on rv.candidate_id=c.id where rv.relation_id is not null)
          update relation set status='evidence_lost',updated_at=clock_timestamp() where id in(select id from lost)
          """,p);
        db.write("""
          update candidate c set status='withdrawn',updated_at=clock_timestamp() where c.user_id=:user and c.status='open'
           and (exists(select 1 from candidate_claim cl join evidence_span e on e.id=cl.evidence_span_id and e.user_id=:user where cl.candidate_id=c.id and e.note_id in (:ids))
            or exists(select 1 from candidate_step s join relation r on r.id=s.relation_id where s.candidate_id=c.id and r.status='evidence_lost'))
          """,p);
    }
}
