package com.example.capstone.analysis.repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;

@Repository
public class AnalysisResultRepository {
    private final AnalysisStore db;
    public AnalysisResultRepository(AnalysisStore db) { this.db=db; }
    public void publish(Work work,PipelineOutput output) {
        for(var item:output.facts()) {
            var fact=item.path("fact");var span=fact.path("evidence");
            var p=base(work,item);p.put("ref",fact.path("factId").asString());p.put("kind",fact.path("kind").asString());
            p.put("subject",fact.path("subjectKey").asString());p.put("profile",item.path("meta").path("profileVersion").asString());
            p.put("value",db.json(fact));p.put("provenance",db.json(item.path("meta")));span(p,span);
            db.write("""
              insert into analysis_fact(id,user_id,job_id,snapshot_id,external_ref,kind,subject_key,profile_version,value,provenance,start_offset,end_offset,quote)
              values(:id,:user,:job,:snapshot,:ref,:kind,:subject,:profile,cast(:value as jsonb),cast(:provenance as jsonb),:start,:end,:quote)
              on conflict(job_id,external_ref) do nothing
              """,p);
        }
        for(var item:output.graphItems()) {
            var p=base(work,item);p.put("kind",item.path("kind").asString());p.put("ref",item.path("ref").asString());p.put("value",db.json(item.path("value")));
            db.write("""
              insert into analysis_graph_item(id,user_id,job_id,snapshot_id,kind,external_ref,value)
              values(:id,:user,:job,:snapshot,:kind,:ref,cast(:value as jsonb)) on conflict(job_id,snapshot_id,kind,external_ref) do nothing
              """,p);
        }
        for(var result:output.results()) {
            var p=new LinkedHashMap<String,Object>();p.put("id",uuid(result,"id"));p.put("user",work.userId());p.put("job",work.id());
            p.put("ref",result.path("ref").asString());p.put("identity",result.path("identitySha256").asString());p.put("kind",result.path("kind").asString());
            p.put("conclusion",result.path("conclusion").asString());p.put("conditions",db.json(result.path("conditions")));
            p.put("trace",db.json(result.path("trace")));p.put("detail",db.json(result.path("detail")));p.put("status",result.path("derivationStatus").asString());
            p.put("soundness",result.path("soundness").asString());p.put("novelty",db.json(result.path("noveltyNoteIds")));
            db.write("""
              insert into analysis_result(id,user_id,job_id,external_ref,identity_sha256,kind,conclusion,conditions,trace,detail,derivation_status,soundness,novelty_note_ids)
              values(:id,:user,:job,:ref,:identity,:kind,:conclusion,cast(:conditions as jsonb),cast(:trace as jsonb),cast(:detail as jsonb),:status,:soundness,cast(:novelty as jsonb))
              on conflict(job_id,external_ref) do nothing
              """,p);
        }
        for(var result:output.results()) {
            UUID resultId=uuid(result,"id");
            for(var evidence:result.path("evidence")) {
                var p=new LinkedHashMap<String,Object>();p.put("id",UUID.randomUUID());p.put("user",work.userId());p.put("job",work.id());p.put("result",resultId);
                p.put("snapshot",uuid(evidence,"snapshotId"));p.put("fact",evidence.path("factId").isNull()||evidence.path("factId").isMissingNode()?null:uuid(evidence,"factId"));
                p.put("claim",evidence.path("claimRef").asString());span(p,evidence);
                JsonNode doc=work.documents().stream().filter(d->d.path("snapshotId").asString().equals(evidence.path("snapshotId").asString())).findFirst().orElseThrow();
                AiResponseValidator.span(doc.path("body").asString(),evidence.path("startOffset").asInt(),evidence.path("endOffset").asInt(),evidence.path("quote").asString());
                db.write("""
                  insert into analysis_result_evidence(id,user_id,job_id,result_id,snapshot_id,fact_id,claim_ref,start_offset,end_offset,quote)
                  values(:id,:user,:job,:result,:snapshot,:fact,:claim,:start,:end,:quote) on conflict(result_id,claim_ref,snapshot_id,start_offset,end_offset) do nothing
                  """,p);
            }
            for(var parent:result.path("dependsOn")) db.write("insert into analysis_dependency(user_id,job_id,result_id,depends_on_id) values(:user,:job,:result,:parent) on conflict do nothing",
                    Map.of("user",work.userId(),"job",work.id(),"result",resultId,"parent",UUID.fromString(parent.asString())));
        }
    }
    private Map<String,Object> base(Work work,JsonNode item) {
        var p=new LinkedHashMap<String,Object>();p.put("id",uuid(item,"id"));p.put("user",work.userId());p.put("job",work.id());p.put("snapshot",uuid(item,"snapshotId"));return p;
    }
    private void span(Map<String,Object> p,JsonNode value) { p.put("start",value.path("startOffset").asInt());p.put("end",value.path("endOffset").asInt());p.put("quote",value.path("quote").asString()); }
    private UUID uuid(JsonNode node,String field) { return UUID.fromString(node.path(field).asString()); }
}
