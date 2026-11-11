package com.example.capstone.candidate.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.client.AiCallException;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.config.AiFeatureGate;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.repository.RecommendationRepository;
import com.example.capstone.candidate.dto.request.RecommendationChatRequest;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

/**
 * 추천 카드를 배경 자료로 AI 챗봇과 여러 턴 대화한다. 대화는 저장하지 않는다(FE가 매번 전체 대화를 보낸다).
 * 첫 글자 전의 오류는 HTTP 오류로, 글이 나온 뒤의 오류는 SSE error 이벤트로 알린다.
 */
@Service
public class RecommendationChatService {
    /** 한 응답 스트림의 최대 길이. AI 요청별 시간 제한보다 길게 둔다. */
    static final long STREAM_TIMEOUT_MS=180_000;
    private final RecommendationRepository repository;
    private final AiHttpClient client;
    private final ObjectMapper mapper;
    private final ChatRelay relay;
    private final AiFeatureGate gate;
    private final AiPipelineConfiguration configuration;
    public RecommendationChatService(RecommendationRepository repository,AiHttpClient client,ObjectMapper mapper,AiFeatureGate gate,AiPipelineConfiguration configuration) {
        this.repository=repository;this.client=client;this.mapper=mapper;this.relay=new ChatRelay(mapper);this.gate=gate;this.configuration=configuration;
    }
    public SseEmitter chat(UUID user,UUID recommendation,RecommendationChatRequest request) {
        JsonNode card=repository.chatCard(user,recommendation);
        if(card==null) throw new ApiException(ErrorCode.NOT_FOUND,"추천을 찾을 수 없습니다.");
        if(!client.configured()) throw unavailable("NOT_DEPLOYED");
        // 분석과 같은 끄기 수단(release 승인·suspension 파일·응답 불일치 격리)을 따른다.
        if(!gate.allowed() || configuration.quarantined()) throw unavailable("MISCONFIGURED");
        AiHttpClient.ChatResponse response;
        try { response=client.chat(payload(card,request)); }
        catch(AiCallException failure) { throw failure.reason()==AiCallException.Reason.INPUT_LIMIT?tooLarge():unavailable(failure.reason()==AiCallException.Reason.TIMEOUT?"TIMEOUT":"UNAVAILABLE"); }
        if(response.status()!=200) throw switch(response.status()) {
            case 413 -> tooLarge();
            case 429 -> unavailable("BUSY");
            case 503 -> unavailable("SERVICE_UNAVAILABLE".equals(response.errorCode())?"CHAT_DISABLED":"UNAVAILABLE");
            default -> unavailable("UNAVAILABLE");
        };
        SseEmitter emitter=new SseEmitter(STREAM_TIMEOUT_MS);
        Runnable close=()->{ try { response.body().close(); } catch(IOException ignored) { } };
        emitter.onTimeout(close);emitter.onError(failure->close.run());emitter.onCompletion(close);
        Thread.ofVirtual().name("recommendation-chat").start(()->{
            try { relay.relay(response.body(),request.conversationId().toString(),(event,data)->emitter.send(SseEmitter.event().name(event).data(data)));emitter.complete(); }
            catch(IOException | RuntimeException disconnected) { close.run();emitter.completeWithError(disconnected); }
        });
        return emitter;
    }
    private ObjectNode payload(JsonNode card,RecommendationChatRequest request) {
        ObjectNode body=mapper.createObjectNode();body.put("conversationId",request.conversationId().toString());body.put("stream",true);
        ObjectNode knowledge=body.putObject("knowledge");
        var keywords=new ArrayList<String>();card.path("keywords").forEach(k->keywords.add(k.asString()));
        knowledge.put("newKeyword",cut(keywords.isEmpty()?"추천":keywords.getLast(),200));
        knowledge.put("conclusion",cut(card.path("conclusion").asString(),2000));
        var evidence=knowledge.putArray("evidence");String[] labels={"A","B"};int index=0;
        for(JsonNode item:card.path("evidence")) {
            if(index>=labels.length) break;
            String keyword=keywords.isEmpty()?"":index==0?keywords.getFirst():keywords.getLast();
            evidence.addObject().put("label",labels[index++]).put("keyword",cut(keyword,200)).put("note",cut(item.path("noteTitle").asString(),200)).put("text",cut(item.path("quote").asString(),2000));
        }
        knowledge.putArray("external");
        var messages=body.putArray("messages");
        for(var message:request.messages()) messages.addObject().put("role",message.role()).put("content",message.content());
        return body;
    }
    private static String cut(String value,int max) { return value.codePointCount(0,value.length())<=max?value:value.substring(0,value.offsetByCodePoints(0,max)); }
    private static ApiException unavailable(String reason) { return new ApiException(ErrorCode.AI_UNAVAILABLE,"챗봇을 지금 사용할 수 없습니다.",Map.of("reason",reason)); }
    private static ApiException tooLarge() { return new ApiException(ErrorCode.CHAT_INPUT_TOO_LARGE,"대화가 너무 깁니다. 새 대화로 다시 시작해 주세요."); }
}
