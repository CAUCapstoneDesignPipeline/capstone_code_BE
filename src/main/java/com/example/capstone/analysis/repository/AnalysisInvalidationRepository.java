package com.example.capstone.analysis.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisInvalidationRepository {
    private final AnalysisStore db;
    public AnalysisInvalidationRepository(AnalysisStore db) { this.db=db; }
    public List<UUID> noteIds(UUID user,UUID topic) { return db.rows("select to_jsonb(id)::text from note where user_id=:user and topic_id=:topic order by id",Map.of("user",user,"topic",topic)).stream().map(n->UUID.fromString(n.asString())).toList(); }
    public void policyChanged(UUID user) {
        List<UUID> ids=db.rows("select to_jsonb(id)::text from note where user_id=:user order by id",Map.of("user",user)).stream().map(n->UUID.fromString(n.asString())).toList();
        invalidate(user,ids,true);
    }
    public void invalidate(UUID user,List<UUID> ids,boolean inputChanged) {
        db.write("update app_user set graph_revision=graph_revision+1 where id=:user",Map.of("user",user));
        if(ids.isEmpty()) return;
        var p=Map.of("user",user,"ids",ids);
        if(inputChanged) db.write("update note set input_revision=input_revision+1 where user_id=:user and id in (:ids)",p);
        db.write("update analysis_snapshot set freshness='stale' where user_id=:user and original_note_id in (:ids) and freshness='current'",p);
        db.write("""
          with recursive affected(id) as (
           select r.id from analysis_result r where r.user_id=:user and (exists(select 1 from analysis_result_evidence e join analysis_snapshot s on s.id=e.snapshot_id and s.user_id=e.user_id
             where e.result_id=r.id and e.user_id=:user and s.original_note_id in (:ids))
             or exists(select 1 from jsonb_array_elements_text(r.novelty_note_ids) n where cast(n as uuid) in (:ids)))
           union select d.result_id from analysis_dependency d join affected a on a.id=d.depends_on_id where d.user_id=:user)
          update analysis_result set freshness='stale' where user_id=:user and id in(select id from affected) and freshness='current'
          """,p);
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
