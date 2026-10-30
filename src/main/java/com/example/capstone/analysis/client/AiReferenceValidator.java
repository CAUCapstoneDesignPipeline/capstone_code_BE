package com.example.capstone.analysis.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import static com.example.capstone.analysis.client.AiResponseValidator.require;

/** Semantic references are not expressible in the exported structural JSON schemas. */
final class AiReferenceValidator {
    private AiReferenceValidator() { }
    static void validate(AiOperation operation, JsonNode request, JsonNode response) {
        Set<String> evidence=ids(request.path("evidence"),"evidenceId");
        switch(operation) {
            case EXTRACT -> {
                Set<String> concepts=ids(response.path("concepts"),"ref");
                Set<String> refs=ids(response.path("evidence"),"ref");
                ids(response.path("relations"),"ref");
                Set<String> types=ids(request.path("policy").path("relationTypes"),"code");
                for(var relation:response.path("relations")) {
                    member(concepts,relation.path("sourceRef")); member(concepts,relation.path("targetRef"));
                    member(types,relation.path("typeCode")); subset(refs,relation.path("evidenceRefs"));
                }
            }
            case MERGE -> require(ids(request.path("pairs"),"pairId").equals(ids(response.path("decisions"),"pairId")));
            case BRIDGES -> require(ids(request.path("pairs"),"pairId").equals(ids(response.path("verdicts"),"pairId")));
            case EDGES -> require(ids(request.path("edges"),"edgeId").equals(ids(response.path("verdicts"),"edgeId")));
            case EXPLAIN -> claims(response.path("explanation"),evidence);
            case VERIFY_EXPLANATION -> {
                claims(request.path("explanation"),evidence);
                require(ids(request.path("explanation").path("claims"),"claimRef").equals(ids(response.path("claimVerdicts"),"claimRef")));
                for(var verdict:response.path("claimVerdicts")) subset(evidence,verdict.path("evidenceIds"));
            }
            case FACTS -> ids(response.path("facts"),"factId");
            case DERIVE -> derivations(request,response);
            case LINKS -> links(request,response);
            case EMBEDDINGS -> ids(request.path("items"),"itemId");
        }
        // All input reasoning objects must refer to evidence supplied in this stage.
        if(!evidence.isEmpty()) walkEvidenceReferences(request,evidence);
    }
    private static void claims(JsonNode explanation,Set<String> evidence) {
        ids(explanation.path("claims"),"claimRef");
        for(var claim:explanation.path("claims")) {
            require("assumption".equals(claim.path("kind").asString()) || !claim.path("evidenceIds").isEmpty());
            subset(evidence,claim.path("evidenceIds"));
        }
    }
    private static void walkEvidenceReferences(JsonNode node,Set<String> evidence) {
        if(node.isObject()) {
            if(node.has("evidenceIds")) subset(evidence,node.path("evidenceIds"));
            for(var field:node.properties()) walkEvidenceReferences(field.getValue(),evidence);
        } else if(node.isArray()) for(var child:node) walkEvidenceReferences(child,evidence);
    }
    private static void derivations(JsonNode request,JsonNode response) {
        Set<String> notes=ids(request.path("documents"),"noteId");
        Set<String> facts=ids(request.path("facts"),"factId");
        require(notes.equals(strings(response.path("noveltyScope").path("noteIds"))));
        Map<String,String> factNotes=new HashMap<>();
        for(var fact:request.path("facts")) factNotes.put(fact.path("factId").asString(),fact.path("evidence").path("noteId").asString());
        Map<String,JsonNode> derivations=new HashMap<>();
        for(var derivation:response.path("derivations")) {
            require(derivations.put(derivation.path("derivationId").asString(),derivation)==null);
            subset(notes,derivation.path("noteIds")); subset(facts,derivation.path("premiseFactIds"));
            Set<String> contributing=new HashSet<>();
            for(var fact:derivation.path("premiseFactIds")) contributing.add(factNotes.get(fact.asString()));
            require(contributing.equals(strings(derivation.path("noteIds"))));
            subset(facts,derivation.path("duplicateOfFactIds"));
            subset(facts,derivation.path("soundness").path("unfaithfulFactIds"));
            for(var condition:derivation.path("conditions")) if(!condition.path("factId").isNull()) member(facts,condition.path("factId"));
            for(var step:derivation.path("steps")) subset(facts,step.path("factIds"));
        }
        for(var derivation:derivations.values()) {
            var dependency=derivation.path("dependsOn");
            if("R1".equals(derivation.path("rule").asString())) require(dependency.isNull());
            else require(!dependency.isNull());
            if(!dependency.isNull()) {
                JsonNode parent=derivations.get(dependency.asString());
                require(parent!=null && "R2".equals(derivation.path("rule").asString())
                        && "R1".equals(parent.path("rule").asString()) && parent.path("dependsOn").isNull());
                for(String key:new String[]{"algorithm","structure"}) require(parent.path(key).path("key").equals(derivation.path(key).path("key")));
                require(parent.path("role").equals(derivation.path("role")));
                require(strings(derivation.path("premiseFactIds")).containsAll(strings(parent.path("premiseFactIds"))));
            }
        }
        require(facts.containsAll(ids(response.path("unusedFacts"),"factId")));
    }
    private static void links(JsonNode request,JsonNode response) {
        Set<String> candidates=ids(request.path("candidates"),"noteId");
        Set<String> actual=ids(response.path("links"),"noteId"); require(candidates.containsAll(actual));
        require(actual.size()<=request.path("k").asInt(10));
        String source=request.path("note").path("noteId").asString();
        Set<String> types=ids(request.path("policy").path("relationTypes"),"code");
        for(var link:response.path("links")) {
            String target=link.path("noteId").asString(); require(!source.equals(target));
            for(var bridge:link.path("bridges")) {
                if(!bridge.path("evidence").isNull()) require(source.equals(bridge.path("evidence").path("noteId").asString()));
                if(!bridge.path("candidateEvidence").isNull()) require(target.equals(bridge.path("candidateEvidence").path("noteId").asString()));
            }
            for(var path:link.path("paths")) {
                Set<String> contributing=new HashSet<>(); Set<Integer> orders=new HashSet<>();
                for(var step:path.path("steps")) {
                    String note=step.path("noteId").asString(); require(Set.of(source,target).contains(note));
                    contributing.add(note); require(orders.add(step.path("stepOrder").asInt())); member(types,step.path("typeCode"));
                    for(var span:step.path("evidence")) require(note.equals(span.path("noteId").asString()));
                    for(var span:step.path("context")) require(note.equals(span.path("noteId").asString()));
                }
                require(contributing.size()==2 && orders.equals(Set.of(1,2)));
                if(!path.path("proposedTypeCode").isNull()) member(types,path.path("proposedTypeCode"));
            }
        }
    }
    static Set<String> ids(JsonNode array,String key) {
        Set<String> result=new HashSet<>(); for(var item:array) require(result.add(item.path(key).asString())); return result;
    }
    private static Set<String> strings(JsonNode array) {
        Set<String> result=new HashSet<>(); for(var item:array) require(result.add(item.asString())); return result;
    }
    private static void member(Set<String> permitted,JsonNode ref) { require(permitted.contains(ref.asString())); }
    private static void subset(Set<String> permitted,JsonNode refs) { require(permitted.containsAll(strings(refs))); }
}
