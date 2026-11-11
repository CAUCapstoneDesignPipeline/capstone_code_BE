package com.example.capstone.candidate.dto.request;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import com.example.capstone.global.validation.TextValidation;

/**
 * 챗봇 요청. 대화는 저장하지 않으므로 FE가 지금까지의 대화를 모두 보낸다.
 * 사용자 발화로 시작하고 끝나며 user·assistant가 번갈아 나온다. 최대 40개, 메시지당 4,000자.
 */
public record RecommendationChatRequest(UUID conversationId,List<Message> messages) {
    public static final int MAX_MESSAGES=40,MAX_CHARS=4000;
    public record Message(String role,String content) { }
    public static RecommendationChatRequest parse(JsonNode value) {
        if(value==null || !value.isObject()) throw invalid("messages");
        UUID conversation=UUID.randomUUID();
        JsonNode supplied=value.path("conversationId");
        if(!supplied.isMissingNode() && !supplied.isNull()) {
            try { conversation=UUID.fromString(supplied.asString());if(!supplied.isString() || !conversation.toString().equalsIgnoreCase(supplied.asString())) throw invalid("conversationId"); }
            catch(IllegalArgumentException wrong) { throw invalid("conversationId"); }
        }
        JsonNode items=value.path("messages");
        if(!items.isArray() || items.isEmpty() || items.size()>MAX_MESSAGES) throw invalid("messages");
        var messages=new ArrayList<Message>();String previous=null;
        for(JsonNode item:items) {
            String role=item.path("role").isString()?item.path("role").asString():null,content=item.path("content").isString()?item.path("content").asString():null;
            if(!"user".equals(role) && !"assistant".equals(role)) throw invalid("messages.role");
            if(content==null || content.isBlank() || content.codePointCount(0,content.length())>MAX_CHARS) throw invalid("messages.content");
            if(role.equals(previous)) throw invalid("messages.role");
            messages.add(new Message(role,content));previous=role;
        }
        if(!messages.getFirst().role().equals("user") || !messages.getLast().role().equals("user")) throw invalid("messages.role");
        return new RecommendationChatRequest(conversation,List.copyOf(messages));
    }
    private static RuntimeException invalid(String field) { return TextValidation.invalid(field,"invalid","대화 내용을 다시 확인해 주세요."); }
}
