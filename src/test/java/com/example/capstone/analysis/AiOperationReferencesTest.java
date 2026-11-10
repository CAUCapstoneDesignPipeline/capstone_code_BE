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
            if(operation==AiOperation.FACTS) continue; // dedicated facts/UTF-16 tests
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
    private JsonNode fixture(AiOperation operation) throws Exception {
        try(var in=getClass().getResourceAsStream("/ai-contract/operations/"+operation.name().toLowerCase()+".json")) { return mapper.readTree(in); }
    }
    private ObjectNode object(JsonNode root,String field,int index) { return (ObjectNode)root.path(field).get(index); }
}
