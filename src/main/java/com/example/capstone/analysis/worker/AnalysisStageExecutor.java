package com.example.capstone.analysis.worker;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.service.AnalysisWorkService;

@Component
public class AnalysisStageExecutor {
    private final AiHttpClient client;
    private final AiResponseValidator validator;
    private final AnalysisWorkService work;
    public AnalysisStageExecutor(AiHttpClient client,AiResponseValidator validator,AnalysisWorkService work) { this.client=client;this.validator=validator;this.work=work; }
    public JsonNode execute(AnalysisPipeline.Work job,String key,AiOperation operation,ObjectNode request) {
        String id=UUID.nameUUIDFromBytes((job.id()+":"+key).getBytes(StandardCharsets.UTF_8)).toString();
        ((ObjectNode)request.path("context")).put("stageExecutionId",id);
        String hash=AiResponseValidator.hash(request.toString());
        JsonNode previous=work.checkpoint(job,key,hash);
        if(previous!=null) { validator.validate(operation,request,previous);return previous; }
        var result=client.call(operation,request,job.deadline(),()->work.reserveAttempt(job,key,operation.name(),request));
        work.checkpoint(job,key,operation.name(),request,result);
        return result.body();
    }
}
