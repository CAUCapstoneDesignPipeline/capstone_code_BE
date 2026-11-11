package com.example.capstone.analysis.support;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/** 합성 원문만 처리하는 HTTP 계약 대역. 실제 추출·판정·모델의 성능을 모사하지 않는다. */
public final class SyntheticPipelineServer implements AutoCloseable {
    public static final String TOKEN="synthetic-pipeline-test-token";
    public static final String POLICY="{\"version\":\"synthetic-1\",\"relationTypes\":[{\"code\":\"supports\",\"label\":\"지원\",\"description\":\"합성 시험\"},{\"code\":\"opposes\",\"label\":\"반대\",\"description\":\"합성 시험\"}],\"compositionRules\":[{\"firstType\":\"supports\",\"secondType\":\"supports\",\"resultType\":\"supports\"}]}";
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public final List<JsonNode> requests=new CopyOnWriteArrayList<>();
    public volatile String mode="normal";
    public volatile String readiness="ready";
    public volatile java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(0),release=new java.util.concurrent.CountDownLatch(0);
    public volatile boolean calculation;
    public volatile String calculationState="sound";
    public final JsonNode derivationFixture=fixture();
    /** true면 합성 meta 대신 AI 404dabf 모양의 meta(ATTESTED_META)로 답한다. derive-sound-1은 판정 유무에 따라 둘 중 하나다. */
    public volatile boolean attested;
    /** null이 아니면 /facts의 역할 있는 사실에 이 roleLabel을 붙인다(새 역할 key의 한국어 이름). */
    public volatile String roleLabel;
    /** AI 404dabf에서 각 profile이 내는 meta. 결정적 profile의 modelRevision은 404dabf 소스의 code_revision 값이고 extract·embeddings는 합성 값이다. */
    public static final java.util.Map<String,List<java.util.Map<String,String>>> ATTESTED_META=attestedMeta();
    private static java.util.Map<String,List<java.util.Map<String,String>>> attestedMeta() {
        var m=new java.util.LinkedHashMap<String,List<java.util.Map<String,String>>>();
        m.put("extract-det-3",List.of(stamp("extract-det-3","local","kiwi-tfidf-markdown","cfg-synthetic+idf-synthetic","rules-synthetic","md-mask-synthetic")));
        m.put("embeddings-bge-m3-1",List.of(stamp("embeddings-bge-m3-1","local","BAAI/bge-m3","5617a9f61b028005a4858fdac845db406aefb181",null,"synthetic-preprocessing")));
        m.put("merge-det-1",List.of(stamp("merge-det-1","local","rules-identity","2bae18113165","merge-rules-1","md-plain-1")));
        m.put("bridges-det-1",List.of(stamp("bridges-det-1","local","rules-identity","2bae18113165","bridges-rules-1","md-plain-1")));
        m.put("edges-det-1",List.of(stamp("edges-det-1","local","rules-entailment-cues","01eac41d61ae","edges-cues-1","md-plain-1")));
        m.put("explain-llm-8",List.of(stamp("explain-llm-8","anthropic","claude-opus-5-5","claude-opus-5-5@6eaae9687edc","explain-llm-8","md-plain-1")));
        m.put("explanation-det-3",List.of(stamp("explanation-det-3","local","rules-explanation-check","0b664d58ae63","explanation-rules-2","md-plain-1")));
        m.put("links-det-1",List.of(stamp("links-det-1","local","kiwi-tfidf-adamic-adar","75b71bb053e0","links-det-1","md-plain-1")));
        m.put("facts-llm-3",List.of(stamp("facts-llm-3","anthropic","claude-opus-5-5","claude-opus-5-5@f378a4a49ec9","facts-llm-3","md-plain-1")));
        m.put("derive-sound-1",List.of(stamp("derive-sound-1","local+anthropic","claude-opus-5-5","claude-opus-5-5@92984930785c","derive-sound-1","md-plain-1"),
                stamp("derive-sound-1","local","llm","92984930785c","derive-sound-1","md-plain-1")));
        return java.util.Collections.unmodifiableMap(m);
    }
    private static java.util.Map<String,String> stamp(String profile,String provider,String model,String revision,String prompt,String preprocessing) {
        var s=new java.util.LinkedHashMap<String,String>();s.put("profileVersion",profile);s.put("provider",provider);s.put("model",model);s.put("modelRevision",revision);
        s.put("promptVersion",prompt);s.put("schemaVersion","1.0");s.put("preprocessingVersion",preprocessing);return java.util.Collections.unmodifiableMap(s);
    }
    private JsonNode fixture() { try(var input=getClass().getResourceAsStream("/ai-contract/operations/derive.json")) { return mapper.readTree(input); } catch(Exception e) { throw new IllegalStateException(e); } }
    public SyntheticPipelineServer() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{
                try(exchange) {
                    if(!("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1);return; }
                    String path=exchange.getRequestURI().getPath().replaceFirst("^/internal/v1","");JsonNode response;
                    if(path.equals("/health/ready")) {
                        if(readiness.equals("timeout"))Thread.sleep(4000);
                        if(readiness.equals("unavailable")) { exchange.sendResponseHeaders(503,-1);return; }
                        var ready=mapper.createObjectNode().put("status","ready").put("schemaVersion","1.0");ready.set("profileVersions",mapper.valueToTree(List.of("extract-det-3","embeddings-bge-m3-1","merge-det-1","bridges-det-1","edges-det-1","explain-llm-8","explanation-det-3","links-det-1","facts-llm-3","derive-sound-1")));if(readiness.equals("missing"))((ArrayNode)ready.path("profileVersions")).remove(8);response=ready;
                    } else {
                        byte[] bytes=exchange.getRequestBody().readNBytes(2097153);if(bytes.length>2097152) { exchange.sendResponseHeaders(413,-1);return; }
                        JsonNode request=mapper.readTree(bytes);requests.add(request);if(mode.equals("blocked") && path.equals("/explanations")) { entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS); }response=respond(path,request);
                    }
                    byte[] encoded=mapper.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
                    String correlation=exchange.getRequestHeaders().getFirst("X-Request-Id");if(correlation!=null) exchange.getResponseHeaders().set("X-Request-Id",correlation);
                    exchange.sendResponseHeaders(200,encoded.length);exchange.getResponseBody().write(encoded);
                } catch(Exception e) { throw new java.io.IOException("Synthetic response failed",e); }
            });server.start();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public URI uri() { return URI.create("http://localhost:"+server.getAddress().getPort()); }
    private ObjectNode base(JsonNode request) {
        var r=mapper.createObjectNode();r.set("context",request.path("context"));var meta=r.putObject("meta");
        meta.set("profileVersion",request.path("profileVersion"));if(mode.equals("profile-mismatch"))meta.put("profileVersion","unsupported-profile");meta.put("provider","synthetic");meta.put("model",request.path("profileVersion").asString());meta.put("modelRevision","synthetic-1");meta.put("promptVersion",request.path("profileVersion").asString());meta.put("schemaVersion","1.0");meta.put("preprocessingVersion","synthetic-1");meta.put("durationMs",1);return r;
    }
    private JsonNode respond(String path,JsonNode q) {
        ObjectNode r=base(q);
        switch(path) {
            case "/extract" -> {
                JsonNode doc=q.path("note");String body=doc.path("body").asString();ArrayNode concepts=r.putArray("concepts"),evidence=r.putArray("evidence"),relations=r.putArray("relations");
                if(body.length()<3 || body.charAt(1)!='>') break;
                String left=body.substring(0,1),right=body.substring(2,3);
                concept(concepts,"left",left,0);concept(concepts,"right",right,2);
                ObjectNode span=span(doc,0,3,false);span.put("ref","e1");evidence.add(span);
                relations.addObject().put("ref","r1").put("sourceRef","left").put("targetRef","right").put("typeCode","supports").put("confidence",1).putArray("evidenceRefs").add("e1");
            }
            case "/embeddings" -> {
                r.put("dimensions",1024);ArrayNode items=r.putArray("items");for(var input:q.path("items")) { var item=items.addObject();item.set("itemId",input.path("itemId"));ArrayNode v=item.putArray("vector");for(int i=0;i<1024;i++) v.add(i==0?1.0:0.0); }
            }
            case "/merge-decisions", "/verifications/bridges" -> {
                boolean merge=path.equals("/merge-decisions");ArrayNode decisions=r.putArray(merge?"decisions":"verdicts");
                for(var pair:q.path("pairs")) decisions.addObject().put("pairId",pair.path("pairId").asString()).put("decision",merge?(mode.equals("bridge-chain")?"uncertain":pair.path("left").path("label").equals(pair.path("right").path("label"))?"same":"different"):(mode.equals("bridge-uncertain")?"uncertain":"same")).put("confidence",0.8).put("reason","합성 판정");
            }
            case "/note-links" -> {
                ArrayNode links=r.putArray("links");JsonNode note=q.path("note");String body=note.path("body").asString();if(body.length()<3 || body.charAt(1)!='>') break;
                for(var candidate:q.path("candidates")) {
                    String other=candidate.path("body").asString();if(other.length()<3 || other.charAt(1)!='>' || other.charAt(0)!=body.charAt(2)) continue;
                    var link=links.addObject().put("noteId",candidate.path("noteId").asString()).put("score",1);
                    var bridge=link.putArray("bridges").addObject().put("label",body.substring(2,3)).put("candidateLabel",other.substring(0,1)).put("confidence",1).put("candidateConfidence",1).put("contribution",1);
                    bridge.set("evidence",span(note,2,3,true));bridge.set("candidateEvidence",span(candidate,0,1,true));
                    var route=link.putArray("paths").addObject().put("source",body.substring(0,1)).put("bridge",body.substring(2,3)).put("target",other.substring(2,3)).put("proposedTypeCode",mode.equals("bad-composition")?"opposes":"supports");
                    ArrayNode steps=route.putArray("steps");linkStep(steps,note,1);linkStep(steps,candidate,2);
                }
            }
            case "/verifications/edges" -> {
                ArrayNode verdicts=r.putArray("verdicts");for(var edge:q.path("edges")) verdicts.addObject().put("edgeId",edge.path("edgeId").asString()).put("outcome",mode.equals("contradiction")?"contradicted":"entailed").put("confidence",1).put("reason","합성 근거 검증");
            }
            case "/facts" -> {
                ArrayNode facts=r.putArray("facts");r.putArray("dropped");if(!calculation)break;
                JsonNode doc=q.path("note");String body=doc.path("body").asString();
                for(var sourceDoc:derivationFixture.path("request").path("documents")) if(body.startsWith(sourceDoc.path("body").asString())) {
                    for(var original:derivationFixture.path("request").path("facts")) if(original.path("evidence").path("noteId").equals(sourceDoc.path("noteId"))) {
                        var fact=(ObjectNode)original.deepCopy();ObjectNode span=(ObjectNode)fact.path("evidence");for(String key:List.of("noteId","noteVersion","bodySha256"))span.set(key,doc.path(key));facts.add(fact);
                    }
                }
                if(body.startsWith("기존 결론")) {
                    var f=facts.addObject().put("factId","stated#1").put("kind","stated_result").put("subject","다익스트라").put("subjectKey","dijkstra").put("role","priority_queue").put("expr","O((V+E)*log(V))").put("case","worst").put("negated",false).put("origin","extracted");
                    for(String field:List.of("op","roleLabel","opLabel","condition","other","otherKey"))f.putNull(field);
                    var e=f.putObject("evidence").put("startOffset",0).put("endOffset",body.length()).put("quote",body);for(String field:List.of("noteId","noteVersion","bodySha256"))e.set(field,doc.path(field));
                }
                if(roleLabel!=null) for(var f:facts) if(f.path("role").isString())((ObjectNode)f).put("roleLabel",roleLabel);
                if(mode.equals("known-change") && body.contains("dijkstra")) for(var f:facts)((ObjectNode)f).put("subjectKey","changed_subject");
                if(mode.equals("overflow") && !facts.isEmpty()) { JsonNode template=facts.get(0).deepCopy();facts.removeAll();for(int i=0;i<300;i++) { ObjectNode fact=(ObjectNode)template.deepCopy();fact.put("factId","synthetic#"+i);facts.add(fact); } }
            }
            case "/derivations" -> {
                boolean hasAlgorithm=q.path("facts").valueStream().anyMatch(f->f.path("kind").asString().equals("needs_op"));
                boolean hasStructure=q.path("facts").valueStream().anyMatch(f->f.path("kind").asString().equals("implements"));
                if(!hasAlgorithm || !hasStructure) { r.putArray("derivations");r.putArray("unusedFacts");var notes=r.putObject("noveltyScope").putArray("noteIds");for(var doc:q.path("documents"))notes.add(doc.path("noteId"));break; }
                var replacement=new java.util.HashMap<String,String>();
                for(var original:derivationFixture.path("request").path("facts")) for(var current:q.path("facts"))
                    if(current.path("kind").equals(original.path("kind")) && current.path("op").equals(original.path("op")) && current.path("evidence").path("quote").equals(original.path("evidence").path("quote"))) {
                        replacement.putIfAbsent(original.path("factId").asString(),current.path("factId").asString());replacement.putIfAbsent(original.path("evidence").path("noteId").asString(),current.path("evidence").path("noteId").asString());
                    }
                ObjectNode output=(ObjectNode)replace(derivationFixture.path("response"),replacement);r.set("derivations",output.path("derivations"));r.set("unusedFacts",output.path("unusedFacts"));
                var notes=r.putObject("noveltyScope").putArray("noteIds");for(var doc:q.path("documents"))notes.add(doc.path("noteId"));
                for(var d:r.path("derivations")) {
                    var row=(ObjectNode)d;String state=calculationState;
                    if(state.equals("missing") && row.path("rule").asString().equals("R1"))state="sound";
                    row.put("status",state.equals("missing")?"held":state.equals("known")?"known":state.equals("rejected")?"rejected":"conclusion");
                    if(state.equals("unchecked") || state.equals("missing"))row.putNull("soundness");
                    else { var sound=row.putObject("soundness").put("verdict",state.equals("rejected")?"unsound":"sound").put("reason","합성 구조 검증");sound.putArray("unfaithfulFactIds"); }
                    if(state.equals("missing"))row.putArray("missing").add("합성 전제 누락");
                    if(mode.equals("condition-only-doc")) {
                        var permitted=new java.util.HashSet<String>();for(var f:q.path("facts"))if(f.path("evidence").path("noteId").equals(q.path("context").path("noteId")))permitted.add(f.path("factId").asString());
                        var premises=mapper.createArrayNode();for(var id:row.path("premiseFactIds"))if(permitted.contains(id.asString()))premises.add(id);row.set("premiseFactIds",premises);row.putArray("noteIds").add(q.path("context").path("noteId"));
                    }
                }
            }
            case "/explanations" -> {
                var explanation=r.putObject("explanation").put("summary","두 원문의 합성 설명");var claims=explanation.putArray("claims");
                int index=0;ArrayNode refs=mapper.createArrayNode();for(var step:q.path("path").path("steps")) { var evidence=step.path("evidenceIds");refs.add(evidence.get(0));var claim=claims.addObject().put("claimRef","premise-"+(++index)).put("kind","premise").put("text","원문 전제 "+index);claim.set("evidenceIds",evidence); }
                if(mode.equals("assumption")) claims.addObject().put("claimRef","assumption").put("kind","assumption").put("text","확인되지 않은 추가 가정").putArray("evidenceIds");
                if(mode.equals("condition")) { var c=claims.addObject().put("claimRef","condition").put("kind","condition").put("text","합성 조건이 성립할 때");c.set("evidenceIds",refs); }
                var conclusion=claims.addObject().put("claimRef","conclusion").put("kind","conclusion").put("text","A는 C를 지원한다");conclusion.set("evidenceIds",refs);
            }
            case "/verifications/explanations" -> {
                r.put("overall","pass");r.putArray("gaps");var verdicts=r.putArray("claimVerdicts");for(var claim:q.path("explanation").path("claims")) { var v=verdicts.addObject().put("claimRef",claim.path("claimRef").asString()).put("outcome","pass").put("reason","합성 검증");v.set("evidenceIds",claim.path("evidenceIds")); }
            }
            default -> throw new IllegalArgumentException("Unsupported synthetic operation "+path);
        }
        if(attested && !mode.equals("profile-mismatch")) {
            var choices=ATTESTED_META.get(q.path("profileVersion").asString());
            // AI는 판정할 결론이 있을 때만 LLM 판정을 부르고 그때만 LLM meta를 낸다(app/api/v1/derive.py).
            boolean judged=r.path("derivations").valueStream().anyMatch(d->!d.path("soundness").isNull());
            var chosen=path.equals("/derivations") && !judged?choices.getLast():choices.getFirst();
            var meta=(ObjectNode)r.path("meta");chosen.forEach(meta::put);
        }
        return r;
    }
    private JsonNode replace(JsonNode node,java.util.Map<String,String> replacements) {
        if(node.isString()) return mapper.valueToTree(replacements.getOrDefault(node.asString(),node.asString()));
        if(node.isObject()) { ObjectNode copy=mapper.createObjectNode();for(var field:node.properties())copy.set(field.getKey(),replace(field.getValue(),replacements));return copy; }
        if(node.isArray()) { ArrayNode copy=mapper.createArrayNode();for(var value:node)copy.add(replace(value,replacements));return copy; }return node.deepCopy();
    }
    private void concept(ArrayNode values,String ref,String label,int offset) { var c=values.addObject().put("ref",ref).put("label",label).put("confidence",1);c.putArray("mentions").addObject().put("startOffset",offset).put("endOffset",offset+1).put("quote",label); }
    private ObjectNode span(JsonNode doc,int start,int end,boolean note) { var s=mapper.createObjectNode().put("startOffset",start).put("endOffset",end).put("quote",doc.path("body").asString().substring(start,end));if(note)s.set("noteId",doc.path("noteId"));return s; }
    private void linkStep(ArrayNode values,JsonNode doc,int order) { String body=doc.path("body").asString();var step=values.addObject().put("stepOrder",order).put("noteId",doc.path("noteId").asString()).put("fromLabel",body.substring(0,1)).put("toLabel",body.substring(2,3)).put("typeCode","supports");step.putArray("evidence").add(span(doc,0,3,true));step.putArray("context"); }
    @Override public void close() { server.stop(0);executor.shutdownNow(); }
}
