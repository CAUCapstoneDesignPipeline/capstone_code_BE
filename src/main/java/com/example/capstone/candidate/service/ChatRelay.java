package com.example.capstone.candidate.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 챗봇 SSE를 공개 이벤트로 바꿔 전달한다. start·delta·done·error만 보내고 thinking·사용량·모델 이름·내부 오류 원문은 보내지 않는다.
 * FE 연결이 끊겨 쓰기가 실패하면 AI 스트림을 닫아 모델 호출도 멈추게 한다.
 */
public final class ChatRelay {
    public static final String INTERRUPTED="CHAT_INTERRUPTED";
    public interface Sink { void send(String event,Map<String,Object> data) throws IOException; }
    private final ObjectMapper mapper;
    public ChatRelay(ObjectMapper mapper) { this.mapper=mapper; }
    public void relay(InputStream upstream,String conversationId,Sink sink) throws IOException {
        try(upstream;var reader=new BufferedReader(new InputStreamReader(upstream,StandardCharsets.UTF_8))) {
            String event=null;var data=new StringBuilder();String line;
            while((line=reader.readLine())!=null) {
                if(line.isEmpty()) {
                    if(event!=null && dispatch(event,data.toString(),conversationId,sink)) return;
                    event=null;data.setLength(0);continue;
                }
                if(line.startsWith("event:")) event=line.substring(6).strip();
                else if(line.startsWith("data:")) { if(!data.isEmpty()) data.append('\n');data.append(line.substring(5).strip()); }
            }
            if(event!=null && dispatch(event,data.toString(),conversationId,sink)) return;
            sink.send("error",interrupted(true));
        }
    }
    /** true면 스트림이 끝났다. */
    private boolean dispatch(String event,String raw,String conversationId,Sink sink) throws IOException {
        JsonNode data;
        try { data=raw.isEmpty()?mapper.createObjectNode():mapper.readTree(raw); } catch(RuntimeException malformed) { sink.send("error",interrupted(true));return true; }
        switch(event) {
            case "start" -> sink.send("start",Map.of("conversationId",conversationId));
            case "delta" -> { if(data.path("text").isString()) sink.send("delta",Map.of("text",data.path("text").asString())); }
            case "done" -> { sink.send("done",Map.of("stopReason",data.path("stopReason").isString()?data.path("stopReason").asString():"end_turn"));return true; }
            case "error" -> { sink.send("error",interrupted(!data.path("retryable").isBoolean() || data.path("retryable").asBoolean()));return true; }
            default -> { }
        }
        return false;
    }
    static Map<String,Object> interrupted(boolean retryable) {
        var value=new LinkedHashMap<String,Object>();value.put("code",INTERRUPTED);value.put("message","답변을 끝까지 만들지 못했습니다. 다시 시도해 주세요.");value.put("retryable",retryable);return value;
    }
}
