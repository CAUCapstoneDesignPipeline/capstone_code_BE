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
            p.put("value",db.json(fact));p.put("provenance",db.json(item.has("provenance")?item.path("provenance"):item.path("meta")));span(p,span);
            db.write("""
              insert into analysis_fact(id,user_id,job_id,snapshot_id,external_ref,kind,subject_key,profile_version,value,provenance,start_offset,end_offset,quote)
              values(:id,:user,:job,:snapshot,:ref,:kind,:subject,:profile,cast(:value as jsonb),cast(:provenance as jsonb),:start,:end,:quote)
              on conflict(job_id,external_ref) do nothing
              """,p);
        }
        for(var item:output.graphItems()) {
            JsonNode doc=work.documents().stream().filter(d->d.path("snapshotId").asString().equals(item.path("snapshotId").asString())).findFirst().orElseThrow();
            if(item.path("kind").asString().equals("relation")) for(var e:item.path("value").path("evidence")) {
                AiResponseValidator.require(e.path("noteId").equals(doc.path("noteId")) && e.path("noteVersion").equals(doc.path("noteVersion")));
                AiResponseValidator.span(doc.path("body").asString(),e.path("startOffset").asInt(-1),e.path("endOffset").asInt(-1),e.path("quote").asString());
            }

            var p=base(work,item);p.put("kind",item.path("kind").asString());p.put("ref",item.path("ref").asString());p.put("value",db.json(item.path("value")));
            db.write("""
              insert into analysis_graph_item(id,user_id,job_id,snapshot_id,kind,external_ref,value)
              values(:id,:user,:job,:snapshot,:kind,:ref,cast(:value as jsonb)) on conflict(job_id,snapshot_id,kind,external_ref) do nothing
              """,p);
        }
        for(var result:output.results()) {
            var p=new LinkedHashMap<String,Object>();p.put("id",uuid(result,"id"));recommendation(work,result,p);p.put("user",work.userId());p.put("job",work.id());
            p.put("ref",result.path("ref").asString());p.put("identity",result.path("identitySha256").asString());p.put("kind",result.path("kind").asString());
            p.put("conclusion",result.path("conclusion").asString());p.put("conditions",db.json(result.path("conditions")));
            p.put("trace",db.json(result.path("trace")));p.put("detail",db.json(result.path("detail")));p.put("status",result.path("derivationStatus").asString());
            p.put("soundness",result.path("soundness").asString());p.put("novelty",db.json(result.path("noveltyNoteIds")));
            db.write("""
              insert into analysis_result(id,user_id,job_id,external_ref,identity_sha256,kind,conclusion,conditions,trace,detail,derivation_status,soundness,novelty_note_ids,
               fingerprint_sha256,recommendation_id,recommendation_revision,content_sha256)
              values(:id,:user,:job,:ref,:identity,:kind,:conclusion,cast(:conditions as jsonb),cast(:trace as jsonb),cast(:detail as jsonb),:status,:soundness,cast(:novelty as jsonb),
               :fingerprint,:recommendation,:recommendationRevision,:content)
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
    /**
     * 후보 지문 = 사용자 + 근거 노트 쌍 + 경로 개념 이름(A-B-C). 개념 id는 노트 version마다 바뀌므로 이름을 정규화해 쓴다.
     * LLM 결론은 지문에 넣지 않는다. 경로가 없는 도출 결과는 결과 identity를 쓴다.
     */
    private void recommendation(Work work,JsonNode result,Map<String,Object> p) {
        var notes=new java.util.TreeSet<String>();var spans=new java.util.TreeSet<String>();
        for(var evidence:result.path("evidence")) {
            String note=work.documents().stream().filter(d->d.path("snapshotId").asString().equals(evidence.path("snapshotId").asString())).findFirst().orElseThrow().path("noteId").asString();
            notes.add(note);spans.add(note+":"+evidence.path("startOffset").asInt()+":"+evidence.path("endOffset").asInt()+":"+evidence.path("quote").asString());
        }
        var labels=new java.util.ArrayList<String>();
        for(var concept:result.path("detail").path("path").path("concepts")) labels.add(java.text.Normalizer.normalize(concept.path("label").asString(),java.text.Normalizer.Form.NFC).strip().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT));
        String path=labels.isEmpty()?"identity:"+result.path("identitySha256").asString():String.join("\u001f",labels);
        String fingerprint=AiResponseValidator.hash("recommendation-fingerprint-v1\u001e"+work.userId()+"\u001e"+String.join(",",notes)+"\u001e"+path);
        String content=AiResponseValidator.hash(result.path("conclusion").asString()+"\u001e"+String.join("\u001f",spans));
        UUID recommendation=UUID.nameUUIDFromBytes(("recommendation:"+fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode previous=db.one("""
          select jsonb_build_object('revision',recommendation_revision,'content',content_sha256)::text from analysis_result
          where user_id=:user and recommendation_id=:recommendation and job_id<>:job order by created_at desc,id desc limit 1
          """,Map.of("user",work.userId(),"recommendation",recommendation,"job",work.id()));
        int revision=previous==null?1:previous.path("revision").asInt()+(previous.path("content").asString().equals(content)?0:1);
        p.put("fingerprint",fingerprint);p.put("recommendation",recommendation);p.put("recommendationRevision",revision);p.put("content",content);
    }
    private Map<String,Object> base(Work work,JsonNode item) {
        var p=new LinkedHashMap<String,Object>();p.put("id",uuid(item,"id"));p.put("user",work.userId());p.put("job",work.id());p.put("snapshot",uuid(item,"snapshotId"));return p;
    }
    private void span(Map<String,Object> p,JsonNode value) { p.put("start",value.path("startOffset").asInt());p.put("end",value.path("endOffset").asInt());p.put("quote",value.path("quote").asString()); }
    private UUID uuid(JsonNode node,String field) { return UUID.fromString(node.path(field).asString()); }
}
