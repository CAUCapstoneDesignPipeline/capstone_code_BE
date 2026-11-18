package com.example.capstone.analysis.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiOperation;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.config.ExternalKnowledgeProperties;
import com.example.capstone.analysis.repository.ExternalKnowledgeRepository;
import com.example.capstone.analysis.repository.RecommendationRepository;

/**
 * 추천 카드의 외부 지식을 채운다. 분석이 끝나면 화면에 보일 카드만 대기열에 넣고, 분석 worker가 쉬는 틈에 한 장씩 AI를 부른다.
 * 외부 지식은 카드의 부가 정보라 실패해도 추천 작업의 outcome을 바꾸지 않고 카드의 externalKnowledge만 null로 남는다.
 */
@Service
public class ExternalKnowledgeService {
    static final String PIPELINE_VERSION="ai-integration-2026-10-30-adopted.1";
    static final Duration CALL_LIMIT=Duration.ofSeconds(130);
    private static final int KEYWORD_LIMIT=200;
    private final ExternalKnowledgeProperties properties;
    private final ExternalKnowledgeRepository repository;
    private final RecommendationRepository recommendations;
    private final AiHttpClient client;
    private final AiPipelineConfiguration configuration;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public ExternalKnowledgeService(ExternalKnowledgeProperties properties,ExternalKnowledgeRepository repository,RecommendationRepository recommendations,
            AiHttpClient client,AiPipelineConfiguration configuration,ObjectMapper mapper,TransactionTemplate transaction) {
        this.properties=properties;this.repository=repository;this.recommendations=recommendations;this.client=client;
        this.configuration=configuration;this.mapper=mapper;this.transaction=transaction;
    }
    /** 분석 완료 트랜잭션 안에서 부른다. DB에만 쓰고 AI는 부르지 않는다. */
    public void enqueue(UUID user,UUID job,UUID note) {
        if(!properties.enabled()) return;
        for(JsonNode card:recommendations.cards(user,job,note,RecommendationRepository.CARD_LIMIT)) repository.enqueue(user,UUID.fromString(card.path("resultId").asString()));
    }
    /** 대기 중인 카드 하나를 처리한다. 처리할 카드가 없거나 기능이 꺼져 있으면 false다. */
    public boolean runOne() {
        if(!properties.enabled() || !client.configured()) return false;
        UUID token=UUID.randomUUID();
        JsonNode row=transaction.execute(status->repository.claim(token));
        if(row==null) return false;
        UUID user=UUID.fromString(row.path("userId").asString()),result=UUID.fromString(row.path("resultId").asString());
        try {
            JsonNode response=client.call(AiOperation.EXTERNAL_KNOWLEDGE,request(row),Instant.now().plus(CALL_LIMIT)).body();
            if(!configuration.accepts(properties.profile(),response.path("meta"))) { release(user,result,token,false);return true; }
            // AI는 뒷받침이 강한 순으로 준다. 카드는 하나만 보이므로 첫 출처를 쓴다.
            JsonNode first=response.path("externalKnowledge").path(0);
            if("found".equals(response.path("status").asString()) && first.isObject())
                transaction.executeWithoutResult(status->repository.found(user,result,token,first.path("summary").asString(),first.path("sourceUrl").asString()));
            else transaction.executeWithoutResult(status->repository.none(user,result,token));
        } catch(AiCallException failure) { release(user,result,token,failure.retryable()); }
        catch(RuntimeException failure) { release(user,result,token,false); }
        return true;
    }
    private void release(UUID user,UUID result,UUID token,boolean retry) {
        transaction.executeWithoutResult(status->repository.release(user,result,token,retry,properties.attempts()));
    }
    /** 결론과 근거 문장 A(요청 노트)·B만 보낸다. 노트 본문 전체는 보내지 않는다. */
    ObjectNode request(JsonNode row) {
        ObjectNode request=mapper.createObjectNode(),context=request.putObject("context");
        String stage=UUID.nameUUIDFromBytes((row.path("resultId").asString()+":external-knowledge:"+row.path("attempts").asInt()).getBytes(StandardCharsets.UTF_8)).toString();
        context.put("analysisJobId",row.path("jobId").asString());context.put("stageExecutionId",stage);context.put("userId",row.path("userId").asString());
        context.put("noteId",row.path("noteId").asString());context.put("noteVersion",row.path("noteVersion").asInt());
        context.put("inputRevision",row.path("inputRevision").asLong());context.put("graphRevision",row.path("graphRevision").asLong());context.put("pipelineVersion",PIPELINE_VERSION);
        request.put("profileVersion",properties.profile());
        ObjectNode claim=request.putObject("claim");claim.put("conclusion",row.path("conclusion").asString());
        var evidence=claim.putArray("evidence");JsonNode keywords=row.path("keywords");
        for(int i=0;i<Math.min(2,row.path("evidence").size());i++) {
            String keyword=keywords.isEmpty()?"":keywords.path(i==0?0:keywords.size()-1).asString();
            evidence.addObject().put("label",i==0?"A":"B").put("keyword",cut(keyword,KEYWORD_LIMIT)).put("text",row.path("evidence").path(i).asString());
        }
        request.put("maxSources",1);
        return request;
    }
    private static String cut(String text,int limit) {
        return text.codePointCount(0,text.length())<=limit?text:text.substring(0,text.offsetByCodePoints(0,limit));
    }
}
