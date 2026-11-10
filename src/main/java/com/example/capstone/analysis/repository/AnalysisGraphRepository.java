package com.example.capstone.analysis.repository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

@Repository
public class AnalysisGraphRepository {
    private final AnalysisStore db;
    public AnalysisGraphRepository(AnalysisStore db) { this.db=db; }
    public List<JsonNode> items(UUID user,String scope,UUID note,UUID topic,boolean unassigned,String kind) {
        var p=parameters(user,scope,note,topic,unassigned);p.put("kind",kind);
        return db.rows("""
          select (g.value || case when g.kind='relation' then jsonb_build_object('status',case when s.freshness='current' and not exists(select 1 from analysis_snapshot stale where stale.job_id=g.job_id and stale.user_id=g.user_id and stale.freshness<>'current') then 'active' else 'evidence_lost' end,
           'evidence',coalesce((select jsonb_agg(e || jsonb_build_object('status',case when s.freshness='current' and not exists(select 1 from analysis_snapshot stale where stale.job_id=g.job_id and stale.user_id=g.user_id and stale.freshness<>'current') then 'valid' else 'lost' end)) from jsonb_array_elements(g.value->'evidence') e),'[]'::jsonb)) else '{}'::jsonb end)::text
          from analysis_graph_item g join analysis_snapshot s on s.id=g.snapshot_id and s.user_id=g.user_id and s.job_id=g.job_id
          join analysis_job j on j.id=g.job_id and j.user_id=g.user_id
          left join note current_note on current_note.id=s.note_id and current_note.user_id=s.user_id
          where g.user_id=:user and g.kind=:kind and j.status='succeeded'
           and (:kind<>'concept' or (s.freshness='current' and not exists(select 1 from analysis_snapshot stale where stale.job_id=g.job_id and stale.user_id=g.user_id and stale.freshness<>'current')))
           and (:scope='all' or (:scope='note' and s.original_note_id=cast(:note as uuid))
             or (:scope='topic' and (current_note.topic_id=cast(:topic as uuid) or (:unassigned=true and current_note.topic_id is null))))
           and not exists(select 1 from analysis_snapshot newer join analysis_job nj on nj.id=newer.job_id and nj.user_id=newer.user_id
            where newer.user_id=s.user_id and newer.original_note_id=s.original_note_id and nj.status='succeeded'
             and (nj.created_at,nj.id)>(j.created_at,j.id)) order by g.id
          """,p);
    }
    public List<JsonNode> reusableAliases(UUID user,List<UUID> notes) {
        return db.rows("""
          select g.value::text from analysis_graph_item g join analysis_job j on j.id=g.job_id and j.user_id=g.user_id
          where g.user_id=:user and g.kind='concept' and j.status='succeeded' and jsonb_typeof(g.value->'aliases')='array'
           and not exists(select 1 from analysis_snapshot s where s.job_id=g.job_id and s.user_id=g.user_id
            and (s.freshness<>'current' or s.original_note_id not in (:notes))) order by g.id
          """,Map.of("user",user,"notes",notes));
    }
    public List<JsonNode> aliases(UUID user) {
        return db.rows("""
          select g.value::text from analysis_graph_item g join analysis_job j on j.id=g.job_id and j.user_id=g.user_id
          where g.user_id=:user and g.kind='concept' and j.status='succeeded' and jsonb_typeof(g.value->'aliases')='array'
           and not exists(select 1 from analysis_snapshot s where s.job_id=g.job_id and s.user_id=g.user_id and s.freshness<>'current') order by g.id
          """,Map.of("user",user));
    }
    public List<JsonNode> legacyConcepts(UUID user,String scope,UUID note,UUID topic,boolean unassigned) {
        return db.rows("""
          select jsonb_build_object('id',c.id,'label',c.label,'mentions',coalesce((select jsonb_agg(jsonb_build_object('surface',m.surface,'startOffset',m.start_offset,'endOffset',m.end_offset))
           from concept_mention m join note n on n.id=m.note_id and n.user_id=c.user_id where m.concept_id=c.id
           and (:scope='all' or (:scope='note' and n.id=cast(:note as uuid)) or (:scope='topic' and (n.topic_id=cast(:topic as uuid) or (:unassigned=true and n.topic_id is null))))),'[]'::jsonb))::text
          from concept c where c.user_id=:user and (:scope='all' or exists(select 1 from concept_mention m join note n on n.id=m.note_id and n.user_id=c.user_id
           where m.concept_id=c.id and ((:scope='note' and n.id=cast(:note as uuid)) or (:scope='topic' and (n.topic_id=cast(:topic as uuid) or (:unassigned=true and n.topic_id is null)))))) order by c.id
          """,parameters(user,scope,note,topic,unassigned));
    }
    public List<JsonNode> legacyRelations(UUID user,String scope,UUID note,UUID topic,boolean unassigned) {
        return db.rows("""
          select jsonb_build_object('id',r.id,'source',jsonb_build_object('id',sc.id,'label',sc.label),'target',jsonb_build_object('id',tc.id,'label',tc.label),
           'type',jsonb_build_object('code',rt.code,'label',rt.label),'origin',r.origin,'status',r.status,'asHypothesis',r.as_hypothesis,
           'evidence',coalesce((select jsonb_agg(jsonb_build_object('id',e.id,'noteId',e.note_id,'noteTitle',n.title,'topic',case when n.id is null then null else jsonb_build_object('id',n.topic_id,'name',coalesce(t.name,'미분류')) end,
             'noteVersion',e.note_version,'startOffset',e.start_offset,'endOffset',e.end_offset,'quote',e.quote,'status',e.status))
             from relation_evidence re join evidence_span e on e.id=re.evidence_span_id and e.user_id=:user left join note n on n.id=e.note_id and n.user_id=e.user_id
             left join topic t on t.id=n.topic_id and t.user_id=n.user_id where re.relation_id=r.id
              and (:scope='all' or (:scope='note' and e.note_id=cast(:note as uuid)) or (:scope='topic' and (n.topic_id=cast(:topic as uuid) or (:unassigned=true and n.topic_id is null))))),'[]'::jsonb))::text
          from relation r join concept sc on sc.id=r.source_concept_id and sc.user_id=:user join concept tc on tc.id=r.target_concept_id and tc.user_id=:user
           join relation_type rt on rt.code=r.type_code where :scope='all' or exists(select 1 from relation_evidence re join evidence_span e on e.id=re.evidence_span_id and e.user_id=:user
             join note n on n.id=e.note_id and n.user_id=e.user_id where re.relation_id=r.id and ((:scope='note' and n.id=cast(:note as uuid))
              or (:scope='topic' and (n.topic_id=cast(:topic as uuid) or (:unassigned=true and n.topic_id is null))))) order by r.id
          """,parameters(user,scope,note,topic,unassigned));
    }
    public JsonNode detailLinks(UUID user,UUID relation) {
        return db.one("""
          select jsonb_build_object('candidateId',(select r.candidate_id from review r join candidate c on c.id=r.candidate_id and c.user_id=:user where r.relation_id=:relation order by r.reviewed_at desc limit 1),
           'derived',coalesce((select jsonb_agg(distinct jsonb_build_object('id',r.id,'source',jsonb_build_object('id',sc.id,'label',sc.label),
             'target',jsonb_build_object('id',tc.id,'label',tc.label),'status',r.status))
             from candidate_step step join candidate c on c.id=step.candidate_id and c.user_id=:user join review rev on rev.candidate_id=c.id
             join relation r on r.id=rev.relation_id join concept sc on sc.id=r.source_concept_id and sc.user_id=:user
             join concept tc on tc.id=r.target_concept_id and tc.user_id=:user where step.relation_id=:relation),'[]'::jsonb))::text
          """,Map.of("user",user,"relation",relation));
    }
    public List<JsonNode> types() { return db.rows("select jsonb_build_object('code',code,'label',label,'description',description)::text from relation_type where active=true order by code",Map.of()); }
    private LinkedHashMap<String,Object> parameters(UUID user,String scope,UUID note,UUID topic,boolean unassigned) {
        var p=new LinkedHashMap<String,Object>();p.put("user",user);p.put("scope",scope);p.put("note",note);p.put("topic",topic);p.put("unassigned",unassigned);return p;
    }
}
