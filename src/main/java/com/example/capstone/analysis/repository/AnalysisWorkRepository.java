package com.example.capstone.analysis.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import com.example.capstone.analysis.client.AiResponseValidator;

/** Bound, fixed SQL for PostgreSQL snapshots, queue claiming and lease compare-and-swap. */
@Repository
public class AnalysisWorkRepository {
    private final AnalysisStore db;
    public AnalysisWorkRepository(AnalysisStore db) { this.db=db; }
    public void retireUnresumable(UUID user,UUID note) {
        cancelObsolete(user);
        db.write("""
          update analysis_job j set status='failed',lifecycle_state='failed',finished_at=clock_timestamp(),lease_token=null,lease_until=null,
           failure_code=case when lifecycle_state is null then 'LEGACY_WORK_NOT_RESUMABLE' else 'TIMEOUT' end
          where j.note_id=:note and j.status in ('pending','running') and (j.lifecycle_state is null or j.deadline<=clock_timestamp())
           and exists(select 1 from note n where n.id=j.note_id and n.user_id=:user)
          """,Map.of("user",user,"note",note));
    }
    public JsonNode active(UUID user,UUID note) {
        return db.one("select to_jsonb(j)::text from analysis_job j where j.note_id=:note and j.status in ('pending','running') and exists(select 1 from note n where n.id=j.note_id and n.user_id=:user)",Map.of("user",user,"note",note));
    }
    public List<JsonNode> documents(UUID user,UUID note) {
        return db.rows("""
            select jsonb_build_object('noteId',n.id,'noteVersion',n.version,'inputRevision',n.input_revision,'title',n.title,'body',n.body,
            'topic',jsonb_build_object('id',n.topic_id,'name',coalesce(t.name,'미분류')))::text
            from note n left join topic t on t.id=n.topic_id and t.user_id=n.user_id
            where n.user_id=:user and (n.id=:note or char_length(n.body)<=50000)
            order by (n.id=:note) desc,
             exists(select 1 from analysis_fact f join analysis_snapshot s on s.id=f.snapshot_id and s.user_id=f.user_id
              where f.user_id=:user and s.original_note_id=n.id and s.freshness='current' and s.note_version=n.version and s.input_revision=n.input_revision
               and exists(select 1 from analysis_fact primary_fact join analysis_snapshot primary_snapshot on primary_snapshot.id=primary_fact.snapshot_id and primary_snapshot.user_id=primary_fact.user_id
                where primary_fact.user_id=:user and primary_snapshot.original_note_id=:note and primary_snapshot.freshness='current' and primary_fact.subject_key=f.subject_key)) desc,
             exists(select 1 from analysis_fact f join analysis_snapshot s on s.id=f.snapshot_id and s.user_id=f.user_id
              where f.user_id=:user and s.original_note_id=n.id and s.freshness='current' and s.note_version=n.version and s.input_revision=n.input_revision and f.kind='stated_result') desc,
             n.updated_at desc,n.id limit 20
            """,Map.of("user",user,"note",note));
    }
    public String latestPolicy(UUID user) {
        JsonNode value=db.one("select to_jsonb(extractor)::text from analysis_job where user_id=:user order by created_at desc,id desc limit 1",Map.of("user",user));return value==null?null:value.asString();
    }
    public void advanceGraph(UUID user) { db.write("update app_user set graph_revision=graph_revision+1 where id=:user",Map.of("user",user)); }
    public long graphRevision(UUID user) {
        return db.one("select to_jsonb(graph_revision)::text from app_user where id=:user",Map.of("user",user)).asLong();
    }
    public void create(UUID id,UUID user,UUID note,int version,long input,long graph,Instant deadline,String extractor) {
        db.write("""
          insert into analysis_job(id,user_id,note_id,note_version,status,extractor,lifecycle_state,input_revision,graph_revision,deadline,contract_revision,runtime_epoch)
          values(:id,:user,:note,:version,'pending',:extractor,'queued',:input,:graph,:deadline,'ai-integration-2026-10-30-adopted.1',(select epoch from analysis_runtime_fence where singleton))
          """,Map.of("id",id,"user",user,"note",note,"version",version,"input",input,"graph",graph,"deadline",deadline,"extractor",extractor));
    }
    public void snapshot(UUID job,UUID user,JsonNode doc) {
        var p=new LinkedHashMap<String,Object>();
        p.put("id",UUID.randomUUID()); p.put("job",job);p.put("user",user);p.put("note",UUID.fromString(doc.path("noteId").asString()));
        p.put("version",doc.path("noteVersion").asInt());p.put("revision",doc.path("inputRevision").asLong());p.put("title",doc.path("title").asString());
        p.put("body",doc.path("body").asString());p.put("hash",AiResponseValidator.hash(doc.path("body").asString()));
        p.put("topic",doc.path("topic").path("id").isNull()?null:UUID.fromString(doc.path("topic").path("id").asString()));
        db.write("""
          insert into analysis_snapshot(id,user_id,job_id,note_id,original_note_id,note_version,input_revision,topic_id,title,body,body_sha256)
          values(:id,:user,:job,:note,:note,:version,:revision,:topic,:title,:body,:hash)
          """,p);
    }
    public JsonNode job(UUID user,UUID job) { return db.one("select to_jsonb(j)::text from analysis_job j where id=:job and user_id=:user",Map.of("user",user,"job",job)); }
    public boolean runtimeCurrent(UUID job) {
        JsonNode row=db.one("select to_jsonb(j.runtime_epoch=f.epoch)::text from analysis_job j cross join analysis_runtime_fence f where j.id=:job and f.singleton",Map.of("job",job));return row!=null && row.asBoolean();
    }
    // 최종 게시 직전에 공유 잠금을 얻는다. 비활성화 응답은 진행 중인 게시 트랜잭션 뒤에 선형화된다.
    public boolean lockRuntimeCurrent(UUID job) {
        lockRuntime();
        return runtimeCurrent(job);
    }
    public void lockRuntime() { db.one("select to_jsonb(epoch)::text from analysis_runtime_fence where singleton for share",Map.of()); }
    public void cancelJob(UUID job) {
        db.write("update analysis_job set status='failed',lifecycle_state='cancelled',failure_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp() where id=:job and lifecycle_state in ('queued','running')",Map.of("job",job));
    }
    private void cancelObsolete(UUID user) {
        db.write("update analysis_job set status='failed',lifecycle_state='cancelled',failure_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp() where user_id=:user and lifecycle_state in ('queued','running') and runtime_epoch<>(select epoch from analysis_runtime_fence where singleton)",Map.of("user",user));
    }
    public void expire() {
        db.write("update analysis_job set status='failed',lifecycle_state='cancelled',failure_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp() where lifecycle_state in ('queued','running') and runtime_epoch<>(select epoch from analysis_runtime_fence where singleton)",Map.of());
        db.write("""
          update analysis_job set status='failed',lifecycle_state='failed',failure_code='TIMEOUT',finished_at=clock_timestamp(),lease_token=null,lease_until=null
          where lifecycle_state in ('queued','running') and (deadline<=clock_timestamp() or (attempt>=3 and lease_until<clock_timestamp()))
          """,Map.of());
    }
    public JsonNode claim(UUID token) {
        expire();
        return db.one("""
          with candidate as (select id from analysis_job where user_id is not null and deadline>clock_timestamp() and attempt<3
           and (lifecycle_state='queued' or (lifecycle_state='running' and lease_until<clock_timestamp())) order by created_at,id for update skip locked limit 1)
          update analysis_job j set status='running',lifecycle_state='running',attempt=attempt+1,lease_token=:token,
           lease_until=least(deadline,clock_timestamp()+interval '60 seconds'),started_at=coalesce(started_at,clock_timestamp())
          from candidate c where j.id=c.id returning to_jsonb(j)::text
          """,Map.of("token",token));
    }
    public List<JsonNode> snapshots(UUID user,UUID job) {
        return db.rows("""
          select jsonb_build_object('snapshotId',s.id,'noteId',s.original_note_id,'noteVersion',s.note_version,'inputRevision',s.input_revision,
           'title',s.title,'body',s.body,'bodySha256',s.body_sha256,'topic',jsonb_build_object('id',s.topic_id,'name',coalesce(t.name,'미분류')))::text
          from analysis_snapshot s left join topic t on t.id=s.topic_id and t.user_id=s.user_id where s.user_id=:user and s.job_id=:job order by s.original_note_id
          """,Map.of("user",user,"job",job));
    }
    public boolean heartbeat(UUID job,UUID token) {
        return db.write("""
          update analysis_job set lease_until=least(deadline,clock_timestamp()+interval '60 seconds')
          where id=:job and runtime_epoch=(select epoch from analysis_runtime_fence where singleton) and lease_token=:token and lifecycle_state='running' and lease_until>clock_timestamp() and deadline>clock_timestamp()
          """,Map.of("job",job,"token",token))==1;
    }
    public boolean lockValid(UUID user,UUID job,UUID token) {
        JsonNode found=db.one("""
          select to_jsonb(j.id)::text from analysis_job j where j.id=:job and j.user_id=:user and j.lease_token=:token
           and j.runtime_epoch=(select epoch from analysis_runtime_fence where singleton) and j.lifecycle_state='running' and j.lease_until>clock_timestamp() and j.deadline>clock_timestamp() for update
          """,Map.of("job",job,"user",user,"token",token));
        return found!=null;
    }
    public boolean inputsCurrent(UUID user,UUID job) {
        return db.one("""
          select to_jsonb(not exists(select 1 from analysis_snapshot s left join note n on n.id=s.note_id and n.user_id=s.user_id
           where s.job_id=:job and s.user_id=:user and (n.id is null or s.freshness<>'current' or n.input_revision<>s.input_revision
           or n.version<>s.note_version or n.body<>s.body or n.topic_id is distinct from s.topic_id))
           and exists(select 1 from analysis_job j join app_user u on u.id=j.user_id where j.id=:job and j.user_id=:user and j.graph_revision=u.graph_revision))::text
          """,Map.of("user",user,"job",job)).asBoolean();
    }
    public boolean finish(UUID job,UUID token,String state,String failure) {
        var p=new LinkedHashMap<String,Object>();p.put("job",job);p.put("token",token);p.put("state",state);p.put("failure",failure);
        return db.write("""
          update analysis_job set status=case when :state='succeeded' then 'succeeded' else 'failed' end,lifecycle_state=:state,
           failure_code=:failure,finished_at=clock_timestamp(),lease_token=null,lease_until=null where id=:job and runtime_epoch=(select epoch from analysis_runtime_fence where singleton) and lease_token=:token and lifecycle_state='running' and lease_until>clock_timestamp() and deadline>clock_timestamp()
          """,p)==1;
    }
    public List<UUID> activeUsers() { return db.rows("select distinct to_jsonb(user_id)::text from analysis_job where user_id is not null and lifecycle_state in ('queued','running') order by 1",Map.of()).stream().map(n->UUID.fromString(n.asString())).toList(); }
    public void cancelUser(UUID user) {
        db.write("""
          update analysis_job set status='failed',lifecycle_state='cancelled',failure_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp()
          where user_id=:user and lifecycle_state in ('queued','running')
          """,Map.of("user",user));
    }
    public JsonNode cachedFacts(UUID user,String key,UUID note) {
        return db.one("""
          select st.response::text from analysis_stage st join analysis_job j on j.id=st.job_id and j.user_id=st.user_id
           join analysis_snapshot s on s.job_id=st.job_id and s.user_id=st.user_id and s.original_note_id=:note
          where st.user_id=:user and st.operation='FACTS' and st.stage_key=:key and j.status='succeeded'
           and s.freshness='current' and not jsonb_exists(st.response,'_pending')
          order by st.created_at desc,st.id desc limit 1
          """,Map.of("user",user,"key",key,"note",note));
    }
    public JsonNode checkpoint(UUID job,String key,String hash) {
        return db.one("select response::text from analysis_stage where job_id=:job and stage_key=:key and request_sha256=:hash and not jsonb_exists(response,'_pending')",Map.of("job",job,"key",key,"hash",hash));
    }
    public boolean reserve(UUID id,UUID user,UUID job,String key,String operation,String hash) {
        return db.one("""
          insert into analysis_stage(id,user_id,job_id,stage_key,operation,request_sha256,response,attempts,duration_ms)
          values(:id,:user,:job,:key,:operation,:hash,'{"_pending":true}',1,0)
          on conflict(job_id,stage_key) do update set attempts=analysis_stage.attempts+1
          where analysis_stage.request_sha256=excluded.request_sha256 and analysis_stage.operation=excluded.operation
           and analysis_stage.attempts<3 and jsonb_exists(analysis_stage.response,'_pending') returning to_jsonb(attempts)::text
          """,Map.of("id",id,"user",user,"job",job,"key",key,"operation",operation,"hash",hash))!=null;
    }
    public void checkpoint(UUID id,UUID user,UUID job,String key,String operation,String hash,JsonNode response,int attempts,long millis) {
        db.write("""
          update analysis_stage set response=cast(:response as jsonb),duration_ms=:millis
          where id=:id and user_id=:user and job_id=:job and stage_key=:key and operation=:operation and request_sha256=:hash
           and jsonb_exists(response,'_pending')
          """,Map.of("id",id,"user",user,"job",job,"key",key,"operation",operation,"hash",hash,"response",db.json(response),"millis",millis));
    }
}
