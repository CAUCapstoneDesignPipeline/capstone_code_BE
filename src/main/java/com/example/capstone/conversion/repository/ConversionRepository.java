package com.example.capstone.conversion.repository;

import java.util.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.*;
import com.example.capstone.conversion.service.SourceStorage.Stored;

@Repository
public class ConversionRepository {
    private final EntityManager em;private final ObjectMapper mapper;
    public ConversionRepository(EntityManager em,ObjectMapper mapper) { this.em=em;this.mapper=mapper; }
    private jakarta.persistence.Query query(String sql,Map<String,?> args) { var q=em.createNativeQuery(sql);args.forEach(q::setParameter);return q; }
    private int write(String sql,Map<String,?> args) { return query(sql,args).executeUpdate(); }
    private List<JsonNode> rows(String sql,Map<String,?> args) { return query(sql,args).getResultList().stream().map(x->mapper.readTree(x.toString())).toList(); }
    private JsonNode one(String sql,Map<String,?> args) { var list=rows(sql,args);return list.isEmpty()?null:list.getFirst(); }
    public void upload(UUID id,UUID user,Stored file) { write("insert into source_file(id,user_id,object_key,media_type,content_sha256,byte_size,page_count) values(:id,:user,:key,:type,:hash,:bytes,:pages)",Map.of("id",id,"user",user,"key",file.key(),"type",file.type(),"hash",file.hash(),"bytes",file.bytes(),"pages",file.pages())); }
    public JsonNode file(UUID user,UUID file,boolean lock) { return one("select to_jsonb(f)::text from source_file f where user_id=:user and id=:file"+(lock?" for update":""),Map.of("user",user,"file",file)); }
    public boolean retained(UUID user,UUID file) { return one("select to_jsonb(exists(select 1 from conversion_confirmation where user_id=:user and file_id=:file and note_id is not null))::text",Map.of("user",user,"file",file)).asBoolean(); }
    public boolean deletedConfirmedNote(UUID user,UUID file) { return one("select to_jsonb(exists(select 1 from conversion_confirmation where user_id=:user and file_id=:file and note_id is null))::text",Map.of("user",user,"file",file)).asBoolean(); }
    public void expireJobs(UUID user) { write("update conversion_job set status='failed',error_code='TIMEOUT',lease_token=null,lease_until=null,finished_at=clock_timestamp() where user_id=:user and status in ('queued','running') and deadline<=clock_timestamp()",Map.of("user",user)); }
    public JsonNode job(UUID user,UUID job,boolean lock) { return one("select to_jsonb(j)::text from conversion_job j where user_id=:user and id=:job"+(lock?" for update":""),Map.of("user",user,"job",job)); }
    public JsonNode replay(UUID user,String key) { return one("select to_jsonb(s)::text from conversion_submission s where user_id=:user and idempotency_key=:key",Map.of("user",user,"key",key)); }
    public JsonNode active(UUID user,UUID file) { return one("select to_jsonb(j)::text from conversion_job j where user_id=:user and file_id=:file and status in ('queued','running')",Map.of("user",user,"file",file)); }
    public void submit(UUID user,UUID file,UUID job,String key) { write("insert into conversion_submission(user_id,file_id,job_id,idempotency_key) values(:user,:file,:job,:key)",Map.of("user",user,"file",file,"job",job,"key",key)); }
    public void create(UUID user,UUID file,UUID id,String key,String engine) { write("insert into conversion_job(id,user_id,file_id,idempotency_key,status,engine_revision) values(:id,:user,:file,:key,'queued',:engine)",Map.of("id",id,"user",user,"file",file,"key",key,"engine",engine)); }
    public JsonNode confirmation(UUID user,UUID job) { return one("select to_jsonb(c)::text from conversion_confirmation c where user_id=:user and job_id=:job",Map.of("user",user,"job",job)); }
    public boolean confirmationKeyTaken(UUID user,String key) { return one("select to_jsonb(exists(select 1 from conversion_confirmation where user_id=:user and idempotency_key=:key))::text",Map.of("user",user,"key",key)).asBoolean(); }
    public void confirm(UUID user,UUID job,UUID file,String key,String requestHash,UUID note,int version,int revision,String mapping,String sourceHash,String markdownHash,JsonNode mappings) {
        var args=new LinkedHashMap<String,Object>();args.put("user",user);args.put("job",job);args.put("file",file);args.put("key",key);args.put("request",requestHash);args.put("note",note);args.put("version",version);args.put("revision",revision);args.put("mapping",mapping);args.put("source",sourceHash);args.put("markdown",markdownHash);args.put("mappings",mappings.toString());
        write("""
          insert into conversion_confirmation(job_id,user_id,file_id,idempotency_key,request_sha256,note_id,original_note_id,note_version,result_revision,mapping_status,source_sha256,markdown_sha256,mappings)
          values(:job,:user,:file,:key,:request,:note,:note,:version,:revision,:mapping,:source,:markdown,cast(:mappings as jsonb))
          """,args);
    }
    public void cancel(UUID user,UUID job) { write("update conversion_job set status='cancelled',conversion_revision=conversion_revision+1,error_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp(),result=null,result_revision=null where user_id=:user and id=:job and status<>'cancelled'",Map.of("user",user,"job",job)); }
    public void tombstone(UUID user,UUID file) {
        write("update source_file set deleted_at=coalesce(deleted_at,clock_timestamp()) where user_id=:user and id=:file",Map.of("user",user,"file",file));
        write("update conversion_job set status='cancelled',conversion_revision=conversion_revision+1,error_code='CANCELLED',lease_token=null,lease_until=null,finished_at=clock_timestamp(),result=null,result_revision=null where user_id=:user and file_id=:file and status<>'cancelled' and not exists(select 1 from conversion_confirmation c where c.job_id=conversion_job.id)",Map.of("user",user,"file",file));
        write("update conversion_confirmation set mapping_status='invalidated',mappings=(select coalesce(jsonb_agg(item || '{\"status\":\"invalidated\"}'::jsonb),'[]'::jsonb) from jsonb_array_elements(mappings) item) where user_id=:user and file_id=:file",Map.of("user",user,"file",file));
    }
    public List<JsonNode> dueFiles() { return rows("""
        select to_jsonb(f)::text from source_file f where
         (f.deleted_at is null and f.expires_at<=clock_timestamp() and not exists(select 1 from conversion_confirmation c where c.file_id=f.id and c.note_id is not null))
         or exists(select 1 from conversion_confirmation c join conversion_job j on j.id=c.job_id and j.user_id=c.user_id
          where c.file_id=f.id and c.note_id is null and (j.result is not null or c.mappings<>'[]'::jsonb or
           (f.deleted_at is null and not exists(select 1 from conversion_confirmation retained where retained.file_id=f.id and retained.note_id is not null))))
        order by f.created_at,f.id limit 100
        """,Map.of()); }
    public void purgeDeletedConfirmationContent(UUID user,UUID file) {
        write("update conversion_job j set result=null,result_revision=null,status='cancelled',error_code='SOURCE_GONE',conversion_revision=conversion_revision+1,lease_token=null,lease_until=null where j.user_id=:user and j.file_id=:file and j.result is not null and exists(select 1 from conversion_confirmation c where c.user_id=j.user_id and c.job_id=j.id and c.note_id is null)",Map.of("user",user,"file",file));
        write("update conversion_confirmation set mappings='[]'::jsonb,mapping_status='invalidated' where user_id=:user and file_id=:file and note_id is null and mappings<>'[]'::jsonb",Map.of("user",user,"file",file));
    }
    public List<JsonNode> pendingDeletes() { return rows("select to_jsonb(f)::text from source_file f where deleted_at is not null and physical_deleted_at is null order by deleted_at,id limit 100",Map.of()); }
    public void deleted(UUID id) { write("update source_file set physical_deleted_at=clock_timestamp() where id=:id and deleted_at is not null",Map.of("id",id)); }
    public boolean referenced(UUID key) { return one("select to_jsonb(exists(select 1 from source_file where object_key=:key))::text",Map.of("key",key)).asBoolean(); }
    public JsonNode claim(UUID token) {
        write("update conversion_job set status='failed',error_code='TIMEOUT',lease_token=null,lease_until=null,finished_at=clock_timestamp() where status in ('queued','running') and (deadline<=clock_timestamp() or (attempt>=2 and lease_until<clock_timestamp()))",Map.of());
        return one("""
          with candidate as (select j.id from conversion_job j join source_file f on f.id=j.file_id and f.user_id=j.user_id
           where f.deleted_at is null and (f.expires_at>clock_timestamp() or exists(select 1 from conversion_confirmation c where c.file_id=f.id and c.note_id is not null))
           and j.deadline>clock_timestamp() and j.attempt<2 and (j.status='queued' or (j.status='running' and j.lease_until<clock_timestamp()))
           order by j.created_at,j.id for update of j skip locked limit 1)
          update conversion_job j set status='running',lease_token=:token,lease_until=least(deadline,clock_timestamp()+interval '150 seconds')
          from candidate c where j.id=c.id returning to_jsonb(j)::text
          """,Map.of("token",token));
    }
    public boolean reserve(UUID job,UUID lease,UUID attempt) { return write("update conversion_job set attempt=attempt+1,attempt_id=:attempt where id=:job and lease_token=:lease and status='running' and lease_until>clock_timestamp() and deadline>clock_timestamp() and attempt<2",Map.of("job",job,"lease",lease,"attempt",attempt))==1; }
    public boolean heartbeat(UUID job,UUID lease) { return write("update conversion_job set lease_until=least(deadline,clock_timestamp()+interval '150 seconds') where id=:job and lease_token=:lease and status='running' and lease_until>clock_timestamp() and deadline>clock_timestamp()",Map.of("job",job,"lease",lease))==1; }
    public boolean valid(JsonNode job,UUID lease) { return job!=null && job.path("status").asString().equals("running") && job.path("lease_token").asString().equals(lease.toString()) && java.time.Instant.parse(job.path("lease_until").asString()).isAfter(java.time.Instant.now()) && java.time.Instant.parse(job.path("deadline").asString()).isAfter(java.time.Instant.now()); }
    public void finish(UUID job,UUID lease,JsonNode response,String failure) { var args=new HashMap<String,Object>();args.put("job",job);args.put("lease",lease);args.put("response",response==null?null:response.toString());args.put("failure",failure);args.put("state",failure==null?"succeeded":"failed");args.put("revision",failure==null?1:null);
        write("update conversion_job set status=:state,error_code=:failure,result=cast(:response as jsonb),result_revision=:revision,lease_token=null,lease_until=null,finished_at=clock_timestamp() where id=:job and lease_token=:lease and status='running' and lease_until>clock_timestamp() and deadline>clock_timestamp()",args); }
}
