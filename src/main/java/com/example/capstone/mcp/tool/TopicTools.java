package com.example.capstone.mcp.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import com.example.capstone.topic.service.TopicService;

@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class TopicTools {

    private static final String LIST_TOPICS_OUTPUT = """
            {"type":"object","properties":{
              "topics":{"type":"array","items":{"type":"object","properties":{
                "id":{"type":"string","format":"uuid"},
                "name":{"type":"string"},
                "sortOrder":{"type":"integer"},
                "noteCount":{"type":"integer"},
                "createdAt":{"type":"string","format":"date-time"}},
                "required":["id","name","sortOrder","noteCount","createdAt"]}},
              "unassignedNoteCount":{"type":"integer"}},
             "required":["topics","unassignedNoteCount"]}
            """;

    private final TopicService topics;
    private final McpToolSupport support;

    public TopicTools(TopicService topics, McpToolSupport support) {
        this.topics = topics;
        this.support = support;
    }

    public SyncToolSpecification listTopics() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("list_topics")
                .description("사용자의 주제(노트 폴더) 목록과 주제별 노트 수, 미분류 노트 수를 돌려준다. "
                        + "주제 이름으로 topicId를 찾을 때 먼저 부른다.")
                .inputSchema(support.schema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"))
                .outputSchema(support.schema(LIST_TOPICS_OUTPUT))
                .annotations(McpToolSupport.hints(true, false, true))
                .build();
        return support.tool(tool, call -> topics.list(call.userId()));
    }
}
