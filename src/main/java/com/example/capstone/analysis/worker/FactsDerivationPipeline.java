package com.example.capstone.analysis.worker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.service.AnalysisWorkService;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput;

/** 사실의 원래 provenance를 보존하고 계산은 기존 AI의 R1/R2 엔진에 위임한다. */
@Component
public class FactsDerivationPipeline {
    private final ObjectMapper mapper;
    private final AnalysisStageExecutor stages;
    private final AnalysisWorkService jobs;
    private final AiPipelineConfiguration configuration;
    private final AiSchemaValidator schemas;
    private final AiResponseValidator validator;
    public FactsDerivationPipeline(ObjectMapper mapper,AnalysisStageExecutor stages,AnalysisWorkService jobs,AiPipelineConfiguration configuration,AiSchemaValidator schemas,AiResponseValidator validator) {
        this.mapper=mapper;this.stages=stages;this.jobs=jobs;this.configuration=configuration;this.schemas=schemas;this.validator=validator;
    }
    public PipelineOutput run(Work work) {
        var known=new TreeSet<String>();var facts=new LinkedHashMap<String,JsonNode>();var rows=new ArrayList<JsonNode>();var stamps=new LinkedHashMap<String,JsonNode>();var documents=new LinkedHashMap<String,JsonNode>();
        work.documents().forEach(doc->documents.put(doc.path("noteId").asString(),doc));var cache=mapper.createArrayNode();
        var ordered=work.documents().stream().sorted(java.util.Comparator.comparing((JsonNode doc)->!doc.path("noteId").asString().equals(work.noteId().toString())).thenComparing(doc->doc.path("noteId").asString())).toList();
        for(var doc:ordered) {
            ObjectNode request=request(work,doc,AiOperation.FACTS);request.set("note",wire(doc));request.set("knownSubjects",mapper.valueToTree(known.stream().limit(500).toList()));
            String domainKey=AiResponseValidator.hash(configuration.fingerprint()+":"+request.path("profileVersion")+":"+AiResponseValidator.canonicalJson(request.path("note"))+":"+request.path("knownSubjects"));
            String key="facts:"+domainKey;JsonNode response=jobs.cachedFacts(work,key,UUID.fromString(doc.path("noteId").asString()));boolean hit=response!=null;
            if(hit) validateCached(work,doc,response);else response=stages.execute(work,key,AiOperation.FACTS,request);
            JsonNode meta=response.path("meta");ObjectNode stamp=(ObjectNode)meta.deepCopy();stamp.remove("durationMs");stamps.put(AiResponseValidator.canonicalJson(stamp),stamp);
            var access=cache.addObject().put("noteId",doc.path("noteId").asString()).put("domainKey",domainKey).put("hit",hit);access.set("sourceContext",response.path("context"));access.set("knownSubjects",request.path("knownSubjects"));access.set("dropped",response.path("dropped"));
            for(var source:response.path("facts")) {
                // 같은 원문을 복사한 노트의 AI FactId가 같아도 입력 문서별 안정 별칭을 만든다.
                String alias=id("fact-alias:"+work.userId()+":"+doc.path("noteId")+":"+domainKey+":"+source.path("factId").asString());
                ObjectNode fact=(ObjectNode)source.deepCopy();fact.put("factId",alias);AiResponseValidator.require(facts.put(alias,fact)==null);
                String rowId=id("fact-row:"+work.id()+":"+alias);var row=mapper.createObjectNode().put("id",rowId);row.set("snapshotId",doc.path("snapshotId"));row.set("fact",fact);row.set("meta",meta);
                var provenance=row.putObject("provenance");provenance.set("meta",meta);provenance.set("sourceFactId",source.path("factId"));provenance.set("sourceContext",response.path("context"));provenance.put("domainKey",domainKey);provenance.put("cacheHit",hit);provenance.put("wireAlias",alias);rows.add(row);
                known.add(fact.path("subjectKey").asString());if(!fact.path("otherKey").isNull())known.add(fact.path("otherKey").asString());
            }
            if(facts.size()>400) throw new AiCallException(AiCallException.Reason.INPUT_LIMIT);
        }
        if(facts.isEmpty()) return new PipelineOutput(rows,List.of(),List.of());
        if(stamps.size()>8) throw new AiCallException(AiCallException.Reason.INPUT_LIMIT);
        ObjectNode request=request(work,documents.get(work.noteId().toString()),AiOperation.DERIVE);ArrayNode docs=request.putArray("documents");work.documents().forEach(doc->docs.add(wire(doc)));
        request.set("facts",mapper.valueToTree(facts.values()));request.set("generationProvenance",mapper.valueToTree(stamps.values()));
        JsonNode response=stages.execute(work,"derive:"+configuration.fingerprint(),AiOperation.DERIVE,request);
        var resultIds=new LinkedHashMap<String,String>();for(var derivation:response.path("derivations")) resultIds.put(derivation.path("derivationId").asString(),id("derivation:"+work.id()+":"+derivation.path("derivationId").asString()));
        var results=new ArrayList<JsonNode>();
        for(var derivation:response.path("derivations")) {
            String ref=derivation.path("derivationId").asString();var result=mapper.createObjectNode().put("id",resultIds.get(ref)).put("ref","derive:"+ref).put("kind","derivation");
            ObjectNode semantic=mapper.createObjectNode();for(String field:List.of("rule","ruleVersion","algorithm","structure","role","case","result","conclusion","conditions"))semantic.set(field,derivation.path(field));
            var premiseValues=semantic.putArray("premises");derivation.path("premiseFactIds").valueStream().map(JsonNode::asString).sorted().forEach(id->premiseValues.add(facts.get(id)));
            semantic.set("scope",mapper.valueToTree(documents.keySet().stream().sorted().toList()));
            result.put("identitySha256",AiResponseValidator.hash(configuration.fingerprint()+":"+AiResponseValidator.canonicalJson(semantic)));result.set("conclusion",derivation.path("conclusion"));
            var conditions=result.putArray("conditions");for(var condition:derivation.path("conditions")) conditions.add(condition.path("text"));
            var trace=result.putArray("trace");for(var step:derivation.path("steps"))trace.add(step.path("text"));
            var detail=result.putObject("detail");detail.set("derivation",derivation);detail.set("independentPremiseNoteIds",derivation.path("noteIds"));detail.set("meta",response.path("meta"));detail.set("factCache",cache);detail.set("unusedFacts",response.path("unusedFacts"));detail.put("unsearchedOutsideScope",true);detail.put("documentLimit",20);detail.put("factLimit",400);detail.putNull("measuredCost");
            result.set("derivationStatus",derivation.path("status"));result.put("soundness",derivation.path("soundness").isNull()?"unchecked":derivation.path("soundness").path("verdict").asString());result.set("noveltyNoteIds",response.path("noveltyScope").path("noteIds"));
            var dependencies=result.putArray("dependsOn");if(!derivation.path("dependsOn").isNull())dependencies.add(resultIds.get(derivation.path("dependsOn").asString()));
            var evidence=result.putArray("evidence");
            for(var premise:derivation.path("premiseFactIds")) evidence(work,evidence,documents,facts,premise.asString(),"premise");
            int index=0;for(var condition:derivation.path("conditions")) { if(!condition.path("factId").isNull()) evidence(work,evidence,documents,facts,condition.path("factId").asString(),"condition-"+index);index++; }
            index=0;for(var step:derivation.path("steps")) { for(var fact:step.path("factIds")) evidence(work,evidence,documents,facts,fact.asString(),"step-"+index);index++; }
            results.add(result);
        }
        return new PipelineOutput(rows,List.of(),results);
    }
    private void validateCached(Work work,JsonNode doc,JsonNode response) {
        schemas.validate("FactsResponse",response);configuration.verify(AiOperation.FACTS,response.path("meta"));
        JsonNode source=response.path("context");AiResponseValidator.require(source.path("userId").asString().equals(work.userId().toString()));
        for(String field:List.of("noteId","noteVersion","inputRevision"))AiResponseValidator.require(AiResponseValidator.sameJson(source.path(field),doc.path(field)));
        ObjectNode artifact=mapper.createObjectNode();artifact.putArray("documents").add(wire(doc));artifact.set("facts",response.path("facts"));validator.validateRequest(artifact);
        var ids=new java.util.HashSet<String>();for(var fact:response.path("facts"))AiResponseValidator.require(ids.add(fact.path("factId").asString()));
    }
    private void evidence(Work work,ArrayNode target,Map<String,JsonNode> documents,Map<String,JsonNode> facts,String id,String claim) {
        JsonNode span=facts.get(id).path("evidence"),doc=documents.get(span.path("noteId").asString());var row=target.addObject();row.set("snapshotId",doc.path("snapshotId"));row.put("factId",id("fact-row:"+work.id()+":"+id));row.put("claimRef",claim);
        for(String field:List.of("startOffset","endOffset","quote")) row.set(field,span.path(field));
    }
    private ObjectNode request(Work work,JsonNode doc,AiOperation operation) {
        var request=mapper.createObjectNode();var context=request.putObject("context").put("analysisJobId",work.id().toString()).put("stageExecutionId",work.id().toString()).put("userId",work.userId().toString());
        for(String field:List.of("noteId","noteVersion","inputRevision"))context.set(field,doc.path(field));context.put("graphRevision",work.graphRevision()).put("pipelineVersion","ai-integration-2026-10-30-adopted.1");request.put("profileVersion",configuration.profile(operation));return request;
    }
    private ObjectNode wire(JsonNode doc) { ObjectNode copy=(ObjectNode)doc.deepCopy();copy.remove("snapshotId");return copy; }
    private String id(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString(); }
}
