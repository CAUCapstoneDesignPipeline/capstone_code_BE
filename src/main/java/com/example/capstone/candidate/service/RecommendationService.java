package com.example.capstone.candidate.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.repository.AnalysisReviewRepository;
import com.example.capstone.analysis.repository.AnalysisWorkRepository;
import com.example.capstone.analysis.repository.RecommendationRepository;
import com.example.capstone.analysis.service.AnalysisService;
import com.example.capstone.candidate.dto.request.RecommendationFeedbackRequest;
import com.example.capstone.candidate.dto.request.ReviewEventRequest;
import com.example.capstone.candidate.dto.response.RecommendationJobResponse;
import com.example.capstone.candidate.dto.response.RecommendationResponse;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.repository.AppUserRepository;

/**
 * 추천 작업은 노트 분석 작업을 공개용 모양으로 투영한 것이다.
 * 대체된 작업은 같은 노트의 최신 작업을 따르고, 끝난 작업은 노트가 지워질 때까지 보관한다.
 */
@Service
public class RecommendationService {
    /** 한 작업이 돌려주는 추천 수 상한. */
    public static final int LIMIT=5;
    public static final String RECOMMENDED="RECOMMENDED",NONE="NO_RECOMMENDATIONS",FAILED="RECOMMENDATION_FAILED";
    private final AnalysisService analysis;
    private final RecommendationRepository repository;
    private final AnalysisWorkRepository work;
    private final AnalysisReviewRepository reviews;
    private final AppUserRepository users;
    private final ObjectMapper mapper;
    public RecommendationService(AnalysisService analysis,RecommendationRepository repository,AnalysisWorkRepository work,AnalysisReviewRepository reviews,AppUserRepository users,ObjectMapper mapper) {
        this.analysis=analysis;this.repository=repository;this.work=work;this.reviews=reviews;this.users=users;this.mapper=mapper;
    }
    /** 같은 노트에 끝나지 않은 작업이 있으면 그 작업을 준다. AI가 꺼져 있으면 분석 요청과 같은 503 AI_UNAVAILABLE이다. */
    public RecommendationJobResponse request(UUID user,UUID note) { return get(user,analysis.request(user,note).id()); }
    @Transactional(readOnly=true)
    public RecommendationJobResponse get(UUID user,UUID id) {
        JsonNode job=repository.job(user,id);if(job==null) throw new ApiException(ErrorCode.NOT_FOUND,"추천 작업을 찾을 수 없습니다.");
        UUID note=UUID.fromString(job.path("noteId").asString());JsonNode effective=job;
        if("superseded".equals(text(job,"lifecycleState"))) {
            JsonNode newer=repository.newestAfter(user,id);
            if(newer!=null) effective=newer;
            else if(work.rerequested(user,note)) return response(job,"pending",null,List.of(),null);
        }
        String state=text(effective,"lifecycleState");if(state==null) state=legacy(text(effective,"status"));
        return switch(state) {
            case "queued","running" -> response(job,"pending",null,List.of(),null);
            case "succeeded" -> {
                List<RecommendationResponse> cards=repository.cards(user,UUID.fromString(effective.path("id").asString()),note,LIMIT).stream().map(this::card).toList();
                yield response(job,"completed",cards.isEmpty()?NONE:RECOMMENDED,cards,instant(effective,"finishedAt"));
            }
            // 연속 대체 상한에 걸려 이어 실행하지 않은 작업은 결과 없이 끝난 것으로 본다.
            case "superseded" -> response(job,"completed",NONE,List.of(),instant(effective,"finishedAt"));
            default -> response(job,"completed",FAILED,List.of(),instant(effective,"finishedAt"));
        };
    }
    /**
     * understood는 채택 검토(새로 이해함)로, known은 거절 검토(이미 앎)로 기록한다. confused는 그래프에 반영하지 않는다.
     * 같은 최종 답을 다시 보내면 204이고, 최종 답 뒤에 다른 답을 보내면 409다. confused 뒤에는 최종 답을 받을 수 있다.
     */
    @Transactional
    public void feedback(UUID user,UUID id,RecommendationFeedbackRequest request) {
        users.lockById(user).orElseThrow(()->new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
        JsonNode result=repository.lockResult(user,id);if(result==null) throw new ApiException(ErrorCode.NOT_FOUND,"추천을 찾을 수 없습니다.");
        String answered=repository.finalAnswer(user,id),action=request.action();
        if(answered!=null) { if(answered.equals(action)) return;throw conflict("이미 답한 추천입니다."); }
        if(action.equals("understood") && (!"conclusion".equals(result.path("derivation_status").asString())
                || !"sound".equals(result.path("soundness").asString()) || !"current".equals(result.path("freshness").asString()))) throw conflict("추천 내용이 바뀌었습니다. 다시 확인해 주세요.");
        if(!action.equals("confused")) {
            String key="recommendation-feedback:"+id+":"+action;
            var review=action.equals("understood")?new ReviewEventRequest(0,"accept",null,"new_understanding",null,key):new ReviewEventRequest(0,"reject","already_known","already_known",null,key);
            reviews.review(user,id,result.path("revision").asInt()+1,review,AiResponseValidator.hash(id+":"+key));
        }
        repository.feedback(user,id,action);
    }
    private RecommendationResponse card(JsonNode row) {
        return new RecommendationResponse(UUID.fromString(row.path("id").asString()),evidence(row.path("evidenceA")),evidence(row.path("evidenceB")),null,
                row.path("conclusion").asString(),mapper.treeToValue(row.path("relatedKeywords"),mapper.getTypeFactory().constructCollectionType(List.class,String.class)));
    }
    private RecommendationResponse.Evidence evidence(JsonNode e) {
        return new RecommendationResponse.Evidence(UUID.fromString(e.path("noteId").asString()),e.path("noteTitle").asString(),e.path("noteVersion").asInt(),
                e.path("startOffset").asInt(),e.path("endOffset").asInt(),e.path("quote").asString());
    }
    private RecommendationJobResponse response(JsonNode job,String status,String outcome,List<RecommendationResponse> cards,Instant completed) {
        return new RecommendationJobResponse(UUID.fromString(job.path("id").asString()),UUID.fromString(job.path("noteId").asString()),status,outcome,cards,instant(job,"createdAt"),completed);
    }
    private static String legacy(String status) { return switch(status) { case "pending" -> "queued";case "running" -> "running";case "succeeded" -> "succeeded";default -> "failed"; }; }
    private static String text(JsonNode node,String key) { return node.path(key).isNull() || node.path(key).isMissingNode()?null:node.path(key).asString(); }
    private static Instant instant(JsonNode node,String key) { String value=text(node,key);return value==null?null:java.time.OffsetDateTime.parse(value).toInstant(); }
    private static ApiException conflict(String message) { return new ApiException(ErrorCode.REVIEW_CONFLICT,message); }
}
