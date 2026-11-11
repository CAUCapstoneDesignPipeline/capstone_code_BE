package com.example.capstone.analysis;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.*;
import static org.junit.jupiter.api.Assertions.*;

class AiOperationReferencesTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final AiSchemaValidator schemas=new AiSchemaValidator(mapper);
    private final AiResponseValidator validator=new AiResponseValidator(schemas);
    @Test void actualAiResponsesRemainCompatibleAndEachOperationRejectsForgedReference() throws Exception {
        for(AiOperation operation:AiOperation.values()) {
            JsonNode fixture=fixture(operation);JsonNode request=fixture.path("request");
            validator.validateRequest(request); validator.validate(operation,request,fixture.path("response"));
            ObjectNode response=(ObjectNode)fixture.path("response").deepCopy();
            switch(operation) {
                case EXTRACT -> object(response,"relations",0).put("sourceRef","foreign");
                case MERGE -> object(response,"decisions",0).put("pairId","foreign");
                case BRIDGES -> object(response,"verdicts",0).put("pairId","foreign");
                case EDGES -> object(response,"verdicts",0).put("edgeId","00000000-0000-4000-8000-000000000099");
                case EXPLAIN -> ((ObjectNode)response.path("explanation").path("claims").get(0)).putArray("evidenceIds");
                case VERIFY_EXPLANATION -> object(response,"claimVerdicts",0).put("claimRef","foreign");
                case EMBEDDINGS -> object(response,"items",0).put("itemId","foreign");
                case LINKS -> object(response,"links",0).put("noteId","00000000-0000-4000-8000-000000000099");
                case DERIVE -> { for(var d:response.path("derivations")) if(d.path("rule").asString().equals("R2")) ((ObjectNode)d).putNull("dependsOn"); }
                case FACTS -> ((ObjectNode)object(response,"facts",0).path("evidence")).put("noteId","00000000-0000-4000-8000-000000000099");
                default -> fail();
            }
            assertThrows(AiCallException.class,()->validator.validate(operation,request,response),operation.name());
        }
    }
    @Test void r2CannotUseAnUnrelatedR1OrDropItsPremises() throws Exception {
        var fixture=fixture(AiOperation.DERIVE);
        for(String mutation:new String[]{"algorithm","structure","role","premises"}) {
            ObjectNode response=(ObjectNode)fixture.path("response").deepCopy();
            for(var row:response.path("derivations")) if(row.path("rule").asString().equals("R2")) {
                ObjectNode r2=(ObjectNode)row;
                if(mutation.equals("role")) r2.put("role","stack");
                else if(mutation.equals("premises")) r2.putArray("premiseFactIds");
                else ((ObjectNode)r2.path(mutation)).put("key","foreign_key");
            }
            assertThrows(AiCallException.class,()->validator.validate(AiOperation.DERIVE,fixture.path("request"),response),mutation);
        }
    }
    @Test void deriveSoundResponsesWithAndWithoutJudgeAreAccepted() throws Exception {
        // AI 404dabf: 판정할 결론이 있으면 LLM 판정 meta, 없으면 규칙 meta. 두 응답 모두 같은 스키마·참조 검사를 통과해야 한다.
        var judged=fixture("derive-sound-judged");validator.validateRequest(judged.path("request"));validator.validate(AiOperation.DERIVE,judged.path("request"),judged.path("response"));
        assertEquals("local+anthropic",judged.path("response").path("meta").path("provider").asString());
        assertEquals("조인 인덱스",judged.path("response").path("derivations").get(0).path("roleLabel").asString());
        assertTrue(judged.path("response").path("derivations").valueStream().allMatch(d->d.path("soundness").isObject()));
        var unjudged=fixture("derive-sound-unjudged");validator.validateRequest(unjudged.path("request"));validator.validate(AiOperation.DERIVE,unjudged.path("request"),unjudged.path("response"));
        assertEquals("local",unjudged.path("response").path("meta").path("provider").asString());assertEquals("llm",unjudged.path("response").path("meta").path("model").asString());
        assertTrue(unjudged.path("response").path("derivations").valueStream().allMatch(d->d.path("soundness").isNull()));
    }
    @Test void openRoleAndOperationKeysNeedTheirLabelFieldsAndKeyShape() throws Exception {
        var facts=fixture(AiOperation.FACTS);
        schemas.validate("FactsRequest",facts.path("request"));
        assertEquals("join_index",facts.path("request").path("knownRoles").get(0).asString());
        for(String mutation:new String[]{"roleCase","roleMissingLabel","emptyLabel","longLabel","opDash"}) {
            ObjectNode response=(ObjectNode)facts.path("response").deepCopy();ObjectNode fact=object(response,"facts",0);
            switch(mutation) {
                case "roleCase" -> fact.put("role","Join_Index");
                case "roleMissingLabel" -> fact.remove("roleLabel");
                case "emptyLabel" -> fact.put("roleLabel","");
                case "longLabel" -> fact.put("roleLabel","가".repeat(81));
                case "opDash" -> object(response,"facts",1).put("op","point-lookup");
                default -> fail();
            }
            assertThrows(AiCallException.class,()->validator.validate(AiOperation.FACTS,facts.path("request"),response),mutation);
        }
        ObjectNode request=(ObjectNode)facts.path("request").deepCopy();request.putArray("knownOps").add("Point Lookup");
        assertThrows(AiCallException.class,()->schemas.validate("FactsRequest",request));
        var derive=fixture("derive-sound-judged");ObjectNode response=(ObjectNode)derive.path("response").deepCopy();object(response,"derivations",0).remove("roleLabel");
        assertThrows(AiCallException.class,()->validator.validate(AiOperation.DERIVE,derive.path("request"),response));
        ObjectNode deriveRequest=(ObjectNode)derive.path("request").deepCopy();object(deriveRequest,"facts",0).remove("opLabel");
        assertThrows(AiCallException.class,()->schemas.validate("DeriveRequest",deriveRequest));
    }
    private JsonNode fixture(AiOperation operation) throws Exception { return fixture(operation.name().toLowerCase()); }
    private JsonNode fixture(String name) throws Exception {
        try(var in=getClass().getResourceAsStream("/ai-contract/operations/"+name+".json")) { return mapper.readTree(in); }
    }
    private ObjectNode object(JsonNode root,String field,int index) { return (ObjectNode)root.path(field).get(index); }
}
