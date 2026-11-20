package com.example.capstone.mcp.tool;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import com.example.capstone.analysis.dto.response.AnalysisJobResponse;
import com.example.capstone.analysis.service.AnalysisService;
import com.example.capstone.candidate.dto.response.RecommendationJobResponse;
import com.example.capstone.candidate.service.RecommendationService;

/**
 * 추천 요청·조회 도구(설계안 2.3 9·10번, M6). 추천 작업은 분석 작업을 공개용으로 투영한 것이라 id가 같다.
 * 요청은 작업을 접수하고 바로 돌아오며, 결과는 get_recommendations로 확인한다.
 */
@Component
@ConditionalOnBooleanProperty("capstone.mcp.enabled")
public class RecommendationTools {

    private static final String JOB_SCHEMA = """
            {"type":["object","null"],"properties":{
              "id":{"type":"string","format":"uuid"},
              "noteId":{"type":"string","format":"uuid"},
              "status":{"type":"string","enum":["pending","completed"]},
              "outcome":{"type":["string","null"],"description":"completed일 때 RECOMMENDED, NO_RECOMMENDATIONS, RECOMMENDATION_FAILED 중 하나"},
              "recommendations":{"type":"array","maxItems":%d,"items":{"type":"object","properties":{
                "id":{"type":"string","format":"uuid"},
                "revision":{"type":"integer"},
                "freshness":{"type":"string","enum":["current","stale","evidence_lost"]},
                "evidenceA":{"$ref":"#/$defs/evidence"},
                "evidenceB":{"$ref":"#/$defs/evidence"},
                "externalKnowledge":{"type":["object","null"],"properties":{
                  "summary":{"type":"string"},"sourceUrl":{"type":"string"}}},
                "conclusion":{"type":"string"},
                "relatedKeywords":{"type":"array","items":{"type":"string"}}},
                "required":["id","revision","freshness","evidenceA","evidenceB","externalKnowledge","conclusion","relatedKeywords"]}},
              "createdAt":{"type":"string","format":"date-time"},
              "completedAt":{"type":["string","null"],"format":"date-time"}},
             "required":["id","noteId","status","outcome","recommendations","createdAt","completedAt"]}
            """.formatted(RecommendationService.LIMIT);
    private static final String OUTPUT = """
            {"type":"object","properties":{"job":%s},"required":["job"],
             "$defs":{"evidence":{"type":"object","properties":{
               "noteId":{"type":"string","format":"uuid"},
               "noteTitle":{"type":"string"},
               "noteVersion":{"type":"integer"},
               "startOffset":{"type":"integer"},
               "endOffset":{"type":"integer"},
               "quote":{"type":"string"}},
               "required":["noteId","noteTitle","noteVersion","startOffset","endOffset","quote"]}}}
            """.formatted(JOB_SCHEMA);

    private final RecommendationService recommendations;
    private final AnalysisService analysis;
    private final McpToolSupport support;

    public RecommendationTools(RecommendationService recommendations, AnalysisService analysis, McpToolSupport support) {
        this.recommendations = recommendations;
        this.analysis = analysis;
        this.support = support;
    }

    public SyncToolSpecification requestRecommendations() {
        String input = """
                {"type":"object","properties":{
                  "noteId":{"type":"string","format":"uuid"}},
                 "required":["noteId"],"additionalProperties":false}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("request_recommendations")
                .description("노트 하나를 분석해 다른 노트와 이어지는 추천(근거 문장 두 개와 결론)을 만들어 달라고 요청한다. "
                        + "사용자가 요청했을 때만 쓴다. 모델 비용이 든다. 바로 끝나지 않으며 작업 상태만 돌려준다. "
                        + "결과는 get_recommendations로 확인한다. 같은 노트에 끝나지 않은 작업이 있으면 그 작업을 돌려준다. "
                        + "AI_UNAVAILABLE이면 다시 시도하지 않고 추천 기능을 지금 쓸 수 없다고 알린다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(OUTPUT))
                .annotations(McpToolSupport.hints(false, false, false))
                .build();
        return support.tool(tool, call -> {
            UUID noteId = call.arguments().requiredUuid("noteId");
            return new JobResult(recommendations.request(call.userId(), noteId));
        });
    }

    public SyncToolSpecification getRecommendations() {
        String input = """
                {"type":"object","properties":{
                  "jobId":{"type":"string","format":"uuid"},
                  "noteId":{"type":"string","format":"uuid"}},
                 "oneOf":[{"required":["jobId"]},{"required":["noteId"]}],
                 "additionalProperties":false}
                """;
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("get_recommendations")
                .description("추천 작업의 상태와 결과를 돌려준다. jobId나 noteId 중 하나만 준다. noteId면 그 노트의 가장 최근 작업이며, "
                        + "분석한 적이 없으면 job이 null이다. status가 pending이면 아직 만드는 중이니 10초 이상 간격으로 최대 6번까지 확인하고, "
                        + "그래도 끝나지 않으면 사용자에게 나중에 다시 물어보라고 안내한다. "
                        + "completed면 outcome이 RECOMMENDED(추천 있음), NO_RECOMMENDATIONS(추천 없음), RECOMMENDATION_FAILED(분석 실패) 중 하나다. "
                        + "분석 실패를 추천이 없다고 전하지 않는다. 추천은 최대 5개다. "
                        + "근거의 quote는 바꾸거나 요약하지 말고 그대로 인용하고 노트 제목(noteTitle)을 함께 적는다. "
                        + "freshness가 current가 아니면 근거가 바뀐 추천이라고 알린다. 이해함·알고 있던 내용 응답은 사용자가 앱에서 한다.")
                .inputSchema(support.schema(input))
                .outputSchema(support.schema(OUTPUT))
                .annotations(McpToolSupport.hints(true, false, true))
                .build();
        return support.tool(tool, call -> {
            McpArguments args = call.arguments();
            boolean byJob = args.has("jobId"), byNote = args.has("noteId");
            if (byJob == byNote) {
                throw McpArguments.invalid(byJob ? "noteId" : "jobId", byJob ? "conflict" : "required");
            }
            if (byJob) {
                return new JobResult(recommendations.get(call.userId(), args.requiredUuid("jobId")));
            }
            AnalysisJobResponse latest = analysis.get(call.userId(), args.requiredUuid("noteId")).job();
            return new JobResult(latest == null ? null : recommendations.get(call.userId(), latest.id()));
        });
    }

    public record JobResult(RecommendationJobResponse job) {
    }
}
