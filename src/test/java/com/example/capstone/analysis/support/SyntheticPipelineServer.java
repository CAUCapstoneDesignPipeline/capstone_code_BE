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
    public SyntheticPipelineServer() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{
                try(exchange) {
                    if(!("Bearer "+TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) { exchange.sendResponseHeaders(401,-1);return; }
                    String path=exchange.getRequestURI().getPath().replaceFirst("^/internal/v1","");JsonNode response;
                    if(path.equals("/health/ready")) {
                        var ready=mapper.createObjectNode().put("status","ready").put("schemaVersion","1.0");ready.set("profileVersions",mapper.valueToTree(List.of("extract-det-3","embeddings-bge-m3-1","merge-det-1","bridges-det-1","edges-det-1","explain-llm-8","explanation-det-3","links-det-1","facts-llm-1","derive-sound-1")));response=ready;
                    } else {
                        byte[] bytes=exchange.getRequestBody().readNBytes(2097153);if(bytes.length>2097152) { exchange.sendResponseHeaders(413,-1);return; }
                        JsonNode request=mapper.readTree(bytes);requests.add(request);response=respond(path,request);
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
        meta.set("profileVersion",request.path("profileVersion"));meta.put("provider","synthetic");meta.put("model",request.path("profileVersion").asString());meta.put("modelRevision","synthetic-1");meta.put("promptVersion",request.path("profileVersion").asString());meta.put("schemaVersion","1.0");meta.put("preprocessingVersion","synthetic-1");meta.put("durationMs",1);return r;
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
        return r;
    }
    private void concept(ArrayNode values,String ref,String label,int offset) { var c=values.addObject().put("ref",ref).put("label",label).put("confidence",1);c.putArray("mentions").addObject().put("startOffset",offset).put("endOffset",offset+1).put("quote",label); }
    private ObjectNode span(JsonNode doc,int start,int end,boolean note) { var s=mapper.createObjectNode().put("startOffset",start).put("endOffset",end).put("quote",doc.path("body").asString().substring(start,end));if(note)s.set("noteId",doc.path("noteId"));return s; }
    private void linkStep(ArrayNode values,JsonNode doc,int order) { String body=doc.path("body").asString();var step=values.addObject().put("stepOrder",order).put("noteId",doc.path("noteId").asString()).put("fromLabel",body.substring(0,1)).put("toLabel",body.substring(2,3)).put("typeCode","supports");step.putArray("evidence").add(span(doc,0,3,true));step.putArray("context"); }
    @Override public void close() { server.stop(0);executor.shutdownNow(); }
}
