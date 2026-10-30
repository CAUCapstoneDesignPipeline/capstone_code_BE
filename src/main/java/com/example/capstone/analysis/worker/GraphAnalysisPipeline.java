package com.example.capstone.analysis.worker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.config.AiPipelineConfiguration;

/** 노트 추출·병합과 두 문서 경로 검증. 계산 사실 도출은 별도 단계로 실행한다. */
@Component
public class GraphAnalysisPipeline implements AnalysisPipeline {
    private final com.example.capstone.analysis.service.AnalysisAliasService aliases;
    private final ObjectMapper mapper;
    private final AnalysisStageExecutor stages;
    private final AiPipelineConfiguration configuration;
    public GraphAnalysisPipeline(ObjectMapper mapper,AnalysisStageExecutor stages,AiPipelineConfiguration configuration,com.example.capstone.analysis.service.AnalysisAliasService aliases) { this.mapper=mapper;this.stages=stages;this.configuration=configuration;this.aliases=aliases; }
    @Override public boolean configured() { return configuration.configured(); }
    @Override public String policyFingerprint() { return configuration.fingerprint(); }
    @Override public Set<String> requiredProfiles() { return configuration.requiredProfiles(); }
    @Override public PipelineOutput run(Work work) {
        var documents=new LinkedHashMap<String,JsonNode>();for(var doc:work.documents()) documents.put(doc.path("noteId").asString(),doc);
        var extracted=new LinkedHashMap<String,JsonNode>();var concepts=new ArrayList<Concept>();var canonical=new Canonical();
        for(var doc:documents.values()) {
            ObjectNode request=request(work,doc,AiOperation.EXTRACT);request.set("note",wireDocument(doc));request.set("policy",configuration.policy());
            JsonNode response=call(work,"extract:"+doc.path("noteId").asString(),AiOperation.EXTRACT,request);extracted.put(doc.path("noteId").asString(),response);
            for(var concept:response.path("concepts")) {
                String id=id("concept:"+work.userId()+":"+doc.path("noteId")+":"+doc.path("noteVersion")+":"+doc.path("inputRevision")+":"+doc.path("bodySha256")+":"+configuration.fingerprint()+":"+concept.path("ref").asString());
                concepts.add(new Concept(id,doc,concept));canonical.add(id);
            }
        }
        for(var row:aliases.reusable(work.userId(),work.documents().stream().map(doc->UUID.fromString(doc.path("noteId").asString())).toList())) { String id=row.path("id").asString();canonical.add(id);for(var alias:row.path("aliases")) { canonical.add(alias.asString());canonical.union(id,alias.asString()); } }
        merge(work,concepts,canonical);
        List<JsonNode> results=paths(work,documents,extracted,concepts,canonical);
        List<JsonNode> graph=graphItems(work,documents,extracted,concepts,canonical);
        return new PipelineOutput(List.of(),graph,results);
    }
    private void merge(Work work,List<Concept> concepts,Canonical canonical) {
        // 문서별 순환 선택으로 병합 검색량만 제한하고 추출 그래프는 전부 보존한다.
        var groups=new LinkedHashMap<String,List<Concept>>();
        concepts.stream().sorted(Comparator.comparing((Concept c)->c.document.path("noteId").asString()).thenComparing(c->c.value.path("label").asString()).thenComparing(c->c.value.path("ref").asString()))
                .forEach(c->groups.computeIfAbsent(c.document.path("noteId").asString(),ignored->new ArrayList<>()).add(c));
        var pool=new ArrayList<Concept>();
        for(int index=0;pool.size()<256;index++) {
            boolean added=false;for(var group:groups.values()) if(index<group.size() && pool.size()<256) { pool.add(group.get(index));added=true; }if(!added) break;
        }
        Map<String,double[]> vectors=new HashMap<>();
        for(int offset=0;offset<pool.size();offset+=32) {
            ObjectNode req=request(work,primary(work),AiOperation.EMBEDDINGS);req.put("expectedDimensions",1024);ArrayNode items=req.putArray("items");
            for(var c:pool.subList(offset,Math.min(offset+32,pool.size()))) items.addObject().put("itemId",c.id).put("text",c.value.path("label").asString());
            JsonNode output=call(work,"embeddings:"+offset,AiOperation.EMBEDDINGS,req);
            for(var item:output.path("items")) { double[] vector=new double[1024];for(int i=0;i<1024;i++) vector[i]=item.path("vector").get(i).asDouble();vectors.put(item.path("itemId").asString(),vector); }
        }
        var pairs=new java.util.PriorityQueue<Pair>(Comparator.comparingDouble(Pair::similarity).thenComparing(p->p.left.id+":"+p.right.id));
        for(int i=0;i<pool.size();i++) for(int j=i+1;j<pool.size();j++) {
            Concept left=pool.get(i),right=pool.get(j);if(left.document.path("noteId").equals(right.document.path("noteId"))) continue;
            Pair pair=new Pair(left,right,cosine(vectors.get(left.id),vectors.get(right.id)));
            pairs.add(pair);if(pairs.size()>32) pairs.remove();
        }
        var ordered=new ArrayList<>(pairs);ordered.sort(Comparator.comparingDouble(Pair::similarity).reversed().thenComparing(p->p.left.id+":"+p.right.id));
        int index=0;
        for(var pair:ordered) {
            ObjectNode leftEvidence=mentionEvidence(pair.left),rightEvidence=mentionEvidence(pair.right);if(leftEvidence==null || rightEvidence==null) continue;
            ObjectNode req=request(work,primary(work),AiOperation.MERGE);req.set("documents",documentArray(List.of(pair.left.document,pair.right.document)));
            req.putArray("evidence").add(leftEvidence).add(rightEvidence);
            var value=req.putArray("pairs").addObject();value.put("pairId","pair");value.set("left",occurrence(pair.left,leftEvidence,canonical));value.set("right",occurrence(pair.right,rightEvidence,canonical));value.put("similarity",pair.similarity);
            JsonNode response=call(work,"merge:"+index++,AiOperation.MERGE,req);
            if(response.path("decisions").get(0).path("decision").asString().equals("same")) canonical.union(pair.left.id,pair.right.id);
        }
    }
    private List<JsonNode> paths(Work work,Map<String,JsonNode> documents,Map<String,JsonNode> extracted,List<Concept> concepts,Canonical canonical) {
        JsonNode primary=primary(work);ArrayNode candidates=mapper.createArrayNode();
        documents.values().stream().filter(d->!d.path("noteId").equals(primary.path("noteId"))).forEach(d->candidates.add(wireDocument(d)));
        if(candidates.isEmpty()) return List.of();
        ObjectNode req=request(work,primary,AiOperation.LINKS);req.set("note",wireDocument(primary));req.set("candidates",candidates);req.set("policy",configuration.policy());req.put("k",10);req.put("minConfidence",0.0);
        JsonNode links=linkBatches(work,req,candidates);var results=new ArrayList<JsonNode>();var seen=new HashSet<String>();var prepared=new ArrayList<Prepared>();int explored=0;
        for(var link:links.path("links")) for(var supplied:link.path("paths")) {
            if(explored>=20) break;if(!seen.add(supplied.toString())) continue;explored++;
            List<JsonNode> steps=new ArrayList<>();supplied.path("steps").forEach(steps::add);steps.sort(Comparator.comparingInt(s->s.path("stepOrder").asInt()));
            JsonNode left=steps.get(0),right=steps.get(1);JsonNode leftDoc=documents.get(left.path("noteId").asString()),rightDoc=documents.get(right.path("noteId").asString());
            Concept a=find(concepts,leftDoc,left.path("fromLabel").asString()),bl=find(concepts,leftDoc,left.path("toLabel").asString()),br=find(concepts,rightDoc,right.path("fromLabel").asString()),c=find(concepts,rightDoc,right.path("toLabel").asString());
            if(a==null || bl==null || br==null || c==null) continue; // AI가 생략한 개념이나 후보를 BE에서 복원하지 않는다.
            JsonNode relation1=findRelation(extracted.get(leftDoc.path("noteId").asString()),a,bl,left.path("typeCode").asString());
            JsonNode relation2=findRelation(extracted.get(rightDoc.path("noteId").asString()),br,c,right.path("typeCode").asString());
            if(relation1==null || relation2==null) continue;
            var evidence=new LinkedHashMap<String,JsonNode>();
            List<String> first=ground(left,leftDoc,evidence),second=ground(right,rightDoc,evidence);
            ObjectNode bridgeLeft=mentionEvidence(bl),bridgeRight=mentionEvidence(br);if(bridgeLeft==null || bridgeRight==null) continue;
            evidence.put(bridgeLeft.path("evidenceId").asString(),bridgeLeft);evidence.put(bridgeRight.path("evidenceId").asString(),bridgeRight);
            String key=AiResponseValidator.hash(supplied.toString());
            ObjectNode bridge=request(work,primary,AiOperation.BRIDGES);bridge.set("documents",documentArray(List.of(leftDoc,rightDoc)));bridge.set("evidence",mapper.valueToTree(evidence.values()));
            var pair=bridge.putArray("pairs").addObject();pair.put("pairId","bridge");pair.set("left",occurrence(bl,bridgeLeft,canonical));pair.set("right",occurrence(br,bridgeRight,canonical));pair.put("similarity",0.0);
            bridge.set("generationProvenance",provenance(links.path("meta")));
            JsonNode bridgeResult=call(work,"bridge:"+key,AiOperation.BRIDGES,bridge);
            if(!bridgeResult.path("verdicts").get(0).path("decision").asString().equals("same")) {
                results.add(outcome(work,key,supplied,evidence,documents,"held","uncertain","매개 개념의 동일성을 확인하지 못했습니다.",List.of(),List.of(bridgeResult.path("verdicts").get(0).path("reason").asString()),bridgeResult));continue;
            }
            canonical.union(bl.id,br.id);
            prepared.add(new Prepared(key,supplied,left,right,leftDoc,rightDoc,a,bl,c,relation1,relation2,evidence,first,second,bridgeResult));
        }
        // 모든 same 판정의 병합을 먼저 마친 뒤 경로·그래프가 같은 최종 UUID를 사용한다.
        for(var candidate:prepared) {
            String key=candidate.key;JsonNode supplied=candidate.supplied,left=candidate.left,right=candidate.right,leftDoc=candidate.leftDoc,rightDoc=candidate.rightDoc;
            Concept a=candidate.a,bl=candidate.bridge,c=candidate.c;JsonNode relation1=candidate.relation1,relation2=candidate.relation2,bridgeResult=candidate.bridgeResult;
            Map<String,JsonNode> evidence=candidate.evidence;List<String> first=candidate.first,second=candidate.second;
            String source=canonical.root(a.id),middle=canonical.root(bl.id),target=canonical.root(c.id);
            if(new HashSet<>(List.of(source,middle,target)).size()!=3) continue;
            ObjectNode path=mapper.createObjectNode();path.put("pathId",id("path:"+work.id()+":"+key));
            var refs=path.putArray("concepts");refs.add(conceptRef(source,a.value.path("label").asString()));refs.add(conceptRef(middle,bl.value.path("label").asString()));refs.add(conceptRef(target,c.value.path("label").asString()));
            path.put("sourceConceptId",source);path.put("bridgeConceptId",middle);path.put("targetConceptId",target);path.set("proposedTypeCode",supplied.path("proposedTypeCode"));
            ArrayNode pathSteps=path.putArray("steps");pathSteps.add(pathStep(1,relationId(work,leftDoc,relation1),source,middle,left.path("typeCode").asString(),first));pathSteps.add(pathStep(2,relationId(work,rightDoc,relation2),middle,target,right.path("typeCode").asString(),second));
            if(supplied.path("proposedTypeCode").isNull()) { results.add(outcome(work,key,path,evidence,documents,"held","unchecked","합성 규칙이 없는 가설입니다.",List.of(),List.of(),bridgeResult));continue; }
            ObjectNode edges=request(work,primary,AiOperation.EDGES);common(edges,leftDoc,rightDoc,evidence);edges.set("policy",configuration.policy());edges.set("generationProvenance",provenance(links.path("meta")));
            ArrayNode checks=edges.putArray("edges");checks.add(edge(pathSteps.get(0),refs.get(0),refs.get(1)));checks.add(edge(pathSteps.get(1),refs.get(1),refs.get(2)));
            JsonNode edgeResult=call(work,"edges:"+key,AiOperation.EDGES,edges);boolean entailed=true,contradicted=false;var edgeReasons=new ArrayList<String>();
            for(var verdict:edgeResult.path("verdicts")) { entailed&=verdict.path("outcome").asString().equals("entailed");contradicted|=verdict.path("outcome").asString().equals("contradicted");edgeReasons.add(verdict.path("reason").asString()); }
            if(!entailed) { results.add(outcome(work,key,path,evidence,documents,contradicted?"rejected":"held",contradicted?"unsound":"uncertain","경로의 근거를 확인하지 못했습니다.",List.of(),edgeReasons,edgeResult));continue; }
            ObjectNode explain=request(work,primary,AiOperation.EXPLAIN);common(explain,leftDoc,rightDoc,evidence);explain.set("path",path);explain.set("policy",configuration.policy());
            JsonNode explanation=call(work,"explain:"+key,AiOperation.EXPLAIN,explain);
            ObjectNode verify=request(work,primary,AiOperation.VERIFY_EXPLANATION);common(verify,leftDoc,rightDoc,evidence);verify.set("path",path);verify.set("policy",configuration.policy());verify.set("explanation",explanation.path("explanation"));verify.set("generationProvenance",provenance(explanation.path("meta")));
            JsonNode verification=call(work,"verify:"+key,AiOperation.VERIFY_EXPLANATION,verify);
            boolean pass=verification.path("overall").asString().equals("pass") && verification.path("gaps").isEmpty(),assumption=false,failed=verification.path("overall").asString().equals("fail");
            for(var verdict:verification.path("claimVerdicts")) {pass&=verdict.path("outcome").asString().equals("pass");failed|=verdict.path("outcome").asString().equals("fail");}
            var conclusions=new ArrayList<String>();var conditions=new ArrayList<String>();var trace=new ArrayList<String>();var contributing=new HashSet<String>();
            for(var claim:explanation.path("explanation").path("claims")) {
                String kind=claim.path("kind").asString(),text=claim.path("text").asString();trace.add(text);assumption|=kind.equals("assumption");
                if(kind.equals("condition")) conditions.add(text);
                if(kind.equals("conclusion")) { conclusions.add(text);for(var ref:claim.path("evidenceIds")) contributing.add(evidence.get(ref.asString()).path("noteId").asString()); }
            }
            AiResponseValidator.require(!conclusions.isEmpty());pass&=!assumption && contributing.size()>=2;
            ObjectNode detail=mapper.createObjectNode();detail.set("path",path);detail.set("explanation",explanation);detail.set("verification",verification);detail.set("bridge",bridgeResult);detail.set("edges",edgeResult);detail.put("hypothesis",assumption);
            results.add(outcome(work,key,path,evidence,documents,failed?"rejected":pass?"conclusion":"held",failed?"unsound":pass?"sound":"uncertain",String.join("\n",conclusions),conditions,trace,detail));
        }
        for(var result:results) ((ObjectNode)result.path("detail")).set("searchScope",links.path("_searchScope"));
        return results;
    }
    private JsonNode linkBatches(Work work,ObjectNode template,ArrayNode candidates) {
        var batches=new ArrayList<ObjectNode>();ObjectNode batch=template.deepCopy();batch.putArray("candidates");
        for(var candidate:candidates) {
            ((ArrayNode)batch.path("candidates")).add(candidate);
            if(mapper.writeValueAsBytes(batch).length>2*1024*1024-16384) {
                ((ArrayNode)batch.path("candidates")).remove(batch.path("candidates").size()-1);
                AiResponseValidator.require(!batch.path("candidates").isEmpty());batches.add(batch);
                batch=template.deepCopy();batch.putArray("candidates").add(candidate);
                AiResponseValidator.require(mapper.writeValueAsBytes(batch).length<=2*1024*1024-16384);
            }
        }
        if(!batch.path("candidates").isEmpty()) batches.add(batch);
        ObjectNode combined=null;var values=new ArrayList<JsonNode>();int index=0;
        for(var request:batches) {
            ObjectNode response=(ObjectNode)call(work,"links:"+index++,AiOperation.LINKS,request);
            if(combined==null) combined=response.deepCopy();response.path("links").forEach(values::add);
        }
        values.sort(Comparator.comparingDouble((JsonNode link)->link.path("score").asDouble()).reversed().thenComparing(link->link.path("noteId").asString()));
        ArrayNode selected=combined.putArray("links");values.stream().limit(10).forEach(selected::add);
        var scope=combined.putObject("_searchScope");scope.put("policyVersion","bounded-search-1");scope.put("globalRankingGuaranteed",false);scope.put("selection","batch-score-heuristic");scope.put("requestByteLimit",2097152);var scopes=scope.putArray("batches");
        for(var request:batches) { var ids=scopes.addArray();for(var candidate:request.path("candidates")) ids.add(candidate.path("noteId")); }
        return combined;
    }
    private List<JsonNode> graphItems(Work work,Map<String,JsonNode> documents,Map<String,JsonNode> extracted,List<Concept> concepts,Canonical canonical) {
        var graph=new ArrayList<JsonNode>();
        for(var concept:concepts) {
            ObjectNode value=mapper.createObjectNode();value.put("id",canonical.root(concept.id));value.put("label",concept.value.path("label").asString());ArrayNode mentions=value.putArray("mentions");
            for(var span:concept.value.path("mentions")) mentions.addObject().put("surface",span.path("quote").asString()).put("startOffset",span.path("startOffset").asInt()).put("endOffset",span.path("endOffset").asInt());
            value.set("aliases",mapper.valueToTree(canonical.members(concept.id)));graph.add(item(work,concept.document,"concept",concept.value.path("ref").asString(),value));
        }
        for(var doc:documents.values()) {
            JsonNode response=extracted.get(doc.path("noteId").asString());var evidence=new HashMap<String,JsonNode>();
            for(var span:response.path("evidence")) { JsonNode value=publicEvidence(doc,span);evidence.put(span.path("ref").asString(),value);graph.add(item(work,doc,"evidence",span.path("ref").asString(),value)); }
            for(var relation:response.path("relations")) {
                Concept source=findRef(concepts,doc,relation.path("sourceRef").asString()),target=findRef(concepts,doc,relation.path("targetRef").asString());String left=canonical.root(source.id),right=canonical.root(target.id);if(left.equals(right)) continue;
                ObjectNode value=mapper.createObjectNode();value.put("id",relationId(work,doc,relation));value.putObject("source").put("id",left).put("label",source.value.path("label").asString());value.putObject("target").put("id",right).put("label",target.value.path("label").asString());
                String type=relation.path("typeCode").asString();value.putObject("type").put("code",type).put("label",typeLabel(type));value.put("origin","extracted");value.put("status","active");value.put("asHypothesis",false);ArrayNode spans=value.putArray("evidence");for(var ref:relation.path("evidenceRefs")) spans.add(evidence.get(ref.asString()));
                graph.add(item(work,doc,"relation",relation.path("ref").asString(),value));
            }
        }
        return graph;
    }
    private ObjectNode outcome(Work work,String key,JsonNode path,Map<String,JsonNode> evidence,Map<String,JsonNode> documents,String status,String soundness,String conclusion,List<String> conditions,List<String> trace,JsonNode detail) {
        ObjectNode semantic=(ObjectNode)path.deepCopy();semantic.remove("pathId");
        String identity=AiResponseValidator.hash(configuration.fingerprint()+":"+semantic+":"+conditions+":"+documents.keySet().stream().sorted().toList());
        ObjectNode r=mapper.createObjectNode();r.put("id",id("result:"+work.id()+":"+identity));r.put("ref","graph:"+key);r.put("identitySha256",identity);r.put("kind","graph");r.put("conclusion",conclusion);r.set("conditions",mapper.valueToTree(conditions));r.set("trace",mapper.valueToTree(trace));
        ObjectNode stored=mapper.createObjectNode();stored.set("assessment",detail);stored.set("path",path);stored.put("mergeCandidateLimit",256);stored.put("mergePairLimit",32);stored.put("pathLimit",20);stored.put("unsearchedOutsideScope",true);stored.put("hypothesis",path.path("proposedTypeCode").isNull());r.set("detail",stored);
        r.put("derivationStatus",status);r.put("soundness",soundness);r.set("noveltyNoteIds",mapper.valueToTree(documents.keySet()));r.putArray("dependsOn");ArrayNode spans=r.putArray("evidence");
        JsonNode claims=detail.path("explanation").path("explanation").path("claims");
        if(claims.isArray()) for(var claim:claims) for(var ref:claim.path("evidenceIds")) addSpan(spans,documents,evidence.get(ref.asString()),claim.path("claimRef").asString());
        else for(var e:evidence.values()) addSpan(spans,documents,e,"path");
        return r;
    }
    private void addSpan(ArrayNode spans,Map<String,JsonNode> documents,JsonNode evidence,String claim) {
        var out=spans.addObject();out.set("snapshotId",documents.get(evidence.path("noteId").asString()).path("snapshotId"));out.put("claimRef",claim);
        for(String field:List.of("startOffset","endOffset","quote")) out.set(field,evidence.path(field));
    }
    private record Prepared(String key,JsonNode supplied,JsonNode left,JsonNode right,JsonNode leftDoc,JsonNode rightDoc,Concept a,Concept bridge,Concept c,JsonNode relation1,JsonNode relation2,Map<String,JsonNode> evidence,List<String> first,List<String> second,JsonNode bridgeResult) { }
    private JsonNode call(Work work,String key,AiOperation operation,ObjectNode request) {
        return stages.execute(work,key+":"+configuration.fingerprint(),operation,request);
    }
    public ObjectNode request(Work work,JsonNode doc,AiOperation operation) {
        ObjectNode req=mapper.createObjectNode();ObjectNode context=req.putObject("context");context.put("analysisJobId",work.id().toString());context.put("stageExecutionId",work.id().toString());context.put("userId",work.userId().toString());
        context.set("noteId",doc.path("noteId"));context.set("noteVersion",doc.path("noteVersion"));context.set("inputRevision",doc.path("inputRevision"));context.put("graphRevision",work.graphRevision());context.put("pipelineVersion","ai-integration-2026-10-30-adopted.1");req.put("profileVersion",configuration.profile(operation));return req;
    }
    private JsonNode primary(Work work) { return work.documents().stream().filter(d->d.path("noteId").asString().equals(work.noteId().toString())).findFirst().orElseThrow(); }
    public ObjectNode wireDocument(JsonNode doc) { ObjectNode copy=(ObjectNode)doc.deepCopy();copy.remove("snapshotId");return copy; }
    private ArrayNode documentArray(List<JsonNode> docs) { var result=mapper.createArrayNode();var seen=new HashSet<String>();for(var doc:docs) if(seen.add(doc.path("noteId").asString())) result.add(wireDocument(doc));return result; }
    private ObjectNode occurrence(Concept concept,JsonNode evidence,Canonical canonical) {
        var o=mapper.createObjectNode();o.put("conceptId",canonical.root(concept.id));o.set("label",concept.value.path("label"));o.set("topicId",concept.document.path("topic").path("id"));o.set("evidenceId",evidence.path("evidenceId"));o.put("surface",evidence.path("quote").asString());return o;
    }
    private ObjectNode mentionEvidence(Concept concept) {
        for(var span:concept.value.path("mentions")) if(span.path("quote").asString().codePointCount(0,span.path("quote").asString().length())<=200) return grounded(concept.document,span);return null;
    }
    private ObjectNode grounded(JsonNode doc,JsonNode span) {
        var e=mapper.createObjectNode();e.put("evidenceId",id("evidence:"+doc.path("noteId")+":"+doc.path("noteVersion")+":"+doc.path("bodySha256")+":"+span.path("startOffset")+":"+span.path("endOffset")));
        for(String key:new String[]{"noteId","noteVersion","bodySha256"}) e.set(key,doc.path(key));for(String key:new String[]{"startOffset","endOffset","quote"}) e.set(key,span.path(key));return e;
    }
    private List<String> ground(JsonNode step,JsonNode doc,Map<String,JsonNode> evidence) {
        var ids=new ArrayList<String>();for(String field:new String[]{"evidence","context"}) for(var span:step.path(field)) {
            ObjectNode e=grounded(doc,span);String id=e.path("evidenceId").asString();evidence.put(id,e);if(field.equals("evidence")) ids.add(id);
        }return ids;
    }
    private ObjectNode publicEvidence(JsonNode doc,JsonNode span) {
        var e=grounded(doc,span);var result=mapper.createObjectNode();result.set("id",e.path("evidenceId"));result.set("noteId",doc.path("noteId"));result.set("noteTitle",doc.path("title"));result.set("topic",doc.path("topic"));result.set("noteVersion",doc.path("noteVersion"));for(String key:new String[]{"startOffset","endOffset","quote"}) result.set(key,span.path(key));result.put("status","valid");return result;
    }
    private Concept find(List<Concept> concepts,JsonNode doc,String label) { List<Concept> found=concepts.stream().filter(c->c.document.path("noteId").equals(doc.path("noteId")) && c.value.path("label").asString().equals(label)).toList();return found.size()==1?found.getFirst():null; }
    private Concept findRef(List<Concept> concepts,JsonNode doc,String ref) { return concepts.stream().filter(c->c.document.path("noteId").equals(doc.path("noteId")) && c.value.path("ref").asString().equals(ref)).findFirst().orElseThrow(); }
    private JsonNode findRelation(JsonNode response,Concept from,Concept to,String type) { for(var r:response.path("relations")) if(r.path("sourceRef").equals(from.value.path("ref")) && r.path("targetRef").equals(to.value.path("ref")) && r.path("typeCode").asString().equals(type)) return r;return null; }
    private ObjectNode pathStep(int order,String relation,String from,String to,String type,List<String> evidence) { var s=mapper.createObjectNode();s.put("stepOrder",order);s.put("relationId",relation);s.put("fromConceptId",from);s.put("toConceptId",to);s.put("typeCode",type);s.set("evidenceIds",mapper.valueToTree(evidence));return s; }
    private ObjectNode edge(JsonNode step,JsonNode source,JsonNode target) { var e=mapper.createObjectNode();e.set("edgeId",step.path("relationId"));e.set("source",source);e.set("target",target);e.set("typeCode",step.path("typeCode"));e.set("evidenceIds",step.path("evidenceIds"));return e; }
    private ObjectNode conceptRef(String id,String label) { return mapper.createObjectNode().put("conceptId",id).put("label",label); }
    private void common(ObjectNode req,JsonNode left,JsonNode right,Map<String,JsonNode> evidence) { req.set("documents",documentArray(List.of(left,right)));req.set("evidence",mapper.valueToTree(evidence.values())); }
    public ArrayNode provenance(JsonNode meta) { ObjectNode stamp=(ObjectNode)meta.deepCopy();stamp.remove("durationMs");return mapper.createArrayNode().add(stamp); }
    private String relationId(Work work,JsonNode doc,JsonNode relation) { return id("relation:"+work.userId()+":"+doc.path("noteId")+":"+doc.path("inputRevision")+":"+doc.path("bodySha256")+":"+configuration.fingerprint()+":"+relation.path("ref")); }
    private ObjectNode item(Work work,JsonNode doc,String kind,String ref,JsonNode value) { var i=mapper.createObjectNode();i.put("id",id(work.id()+":"+doc.path("noteId")+":"+kind+":"+ref));i.set("snapshotId",doc.path("snapshotId"));i.put("kind",kind);i.put("ref",ref);i.set("value",value);return i; }
    private String typeLabel(String code) { for(var type:configuration.policy().path("relationTypes")) if(type.path("code").asString().equals(code)) return type.path("label").asString();throw new AiCallException(AiCallException.Reason.INVALID_RESPONSE); }
    private static String id(String text) { return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString(); }
    private double cosine(double[] left,double[] right) { double dot=0,a=0,b=0;for(int i=0;i<left.length;i++){dot+=left[i]*right[i];a+=left[i]*left[i];b+=right[i]*right[i];}AiResponseValidator.require(a>0 && b>0);return Math.max(-1,Math.min(1,dot/Math.sqrt(a*b))); }
    private record Concept(String id,JsonNode document,JsonNode value) { }
    private record Pair(Concept left,Concept right,double similarity) { }
    private static final class Canonical {
        private final Map<String,String> parent=new HashMap<>();
        void add(String id) { parent.putIfAbsent(id,id); }
        String root(String id) { String p=parent.get(id);if(p.equals(id))return id;String root=root(p);parent.put(id,root);return root; }
        void union(String a,String b) { String left=root(a),right=root(b);if(left.compareTo(right)<=0)parent.put(right,left);else parent.put(left,right); }
        List<String> members(String id) { String root=root(id);return parent.keySet().stream().filter(key->root(key).equals(root)).sorted().toList(); }
    }
}
