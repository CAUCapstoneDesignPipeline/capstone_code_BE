package com.example.capstone.candidate.service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.repository.AnalysisReviewRepository;
import com.example.capstone.candidate.dto.request.ReviewEventRequest;
import com.example.capstone.candidate.dto.response.InsightListResponse;
import com.example.capstone.candidate.dto.response.InsightResponse;
import com.example.capstone.candidate.dto.response.ReviewEventResponse;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.user.repository.AppUserRepository;

@Service
public class InsightService {
    private final AnalysisReviewRepository repository;
    private final AppUserRepository users;
    private final ObjectMapper mapper;
    public InsightService(AnalysisReviewRepository repository,AppUserRepository users,ObjectMapper mapper) { this.repository=repository;this.users=users;this.mapper=mapper; }
    @Transactional(readOnly=true) public InsightListResponse list(UUID user,String view,int limit) {
        if(!Set.of("recommendations","history").contains(view) || limit<1 || limit>5) throw invalid();
        return new InsightListResponse(repository.list(user,view.equals("recommendations"),limit).stream().map(n->mapper.treeToValue(n,InsightResponse.class)).toList(),true);
    }
    @Transactional(readOnly=true) public InsightResponse get(UUID user,UUID id) {
        JsonNode result=repository.insight(user,id);if(result==null) throw missing();return mapper.treeToValue(result,InsightResponse.class);
    }
    @Transactional public ReviewEventResponse review(UUID user,UUID id,ReviewEventRequest supplied) {
        ReviewEventRequest request=validate(supplied);
        users.lockById(user).orElseThrow(()->new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
        JsonNode current=repository.lock(user,id);if(current==null) throw missing();
        String hash=AiResponseValidator.hash(id+":"+mapper.writeValueAsString(request));
        JsonNode prior=repository.replay(user,request.idempotencyKey());
        if(prior!=null) {
            if(!prior.path("result_id").asString().equals(id.toString()) || !prior.path("request_sha256").asString().equals(hash)) throw conflict();
            return event(prior);
        }
        if(current.path("revision").asInt()!=request.expectedRevision()) throw conflict();
        if(request.action().equals("accept") && (!current.path("derivation_status").asString().equals("conclusion")
                || !current.path("soundness").asString().equals("sound") || !current.path("freshness").asString().equals("current"))) throw conflict();
        repository.review(user,id,request.expectedRevision()+1,request,hash);
        return event(repository.replay(user,request.idempotencyKey()));
    }
    @Transactional(readOnly=true) public List<ReviewEventResponse> reviews(UUID user) { return repository.reviews(user).stream().map(this::event).toList(); }
    private ReviewEventRequest validate(ReviewEventRequest r) {
        if(r.expectedRevision()==null || r.expectedRevision()<1 || r.expectedRevision()==Integer.MAX_VALUE || r.action()==null || !Set.of("accept","reject","modify").contains(r.action())
                || r.idempotencyKey()==null || r.idempotencyKey().isBlank() || r.idempotencyKey().codePointCount(0,r.idempotencyKey().length())>80) throw invalid();
        if(r.reason()!=null && !Set.of("wrong","already_known","in_source","not_helpful").contains(r.reason())) throw invalid();
        if(r.learning()!=null && !Set.of("already_known","new_understanding").contains(r.learning())) throw invalid();
        if(r.action().equals("reject") && r.reason()==null) throw invalid();
        String edited=r.editedConclusion()==null?null:Normalizer.normalize(r.editedConclusion(),Normalizer.Form.NFC);
        if(r.action().equals("modify")) { if(edited==null || edited.isBlank() || edited.codePointCount(0,edited.length())>4000) throw invalid(); }
        else if(edited!=null) throw invalid();
        return new ReviewEventRequest(r.expectedRevision(),r.action(),r.reason(),r.learning(),edited,r.idempotencyKey());
    }
    private ReviewEventResponse event(JsonNode e) {
        return new ReviewEventResponse(UUID.fromString(e.path("id").asString()),UUID.fromString(e.path("result_id").asString()),e.path("revision").asInt(),e.path("action").asString(),
                nullable(e,"reason"),nullable(e,"learning"),nullable(e,"edited_conclusion"),Instant.parse(e.path("created_at").asString()));
    }
    private String nullable(JsonNode n,String key) { return n.path(key).isNull()?null:n.path(key).asString(); }
    private ApiException missing() { return new ApiException(ErrorCode.NOT_FOUND,"결과를 찾을 수 없습니다."); }
    private ApiException conflict() { return new ApiException(ErrorCode.REVIEW_CONFLICT,"검토 상태가 변경되었습니다. 다시 확인해 주세요."); }
    private ApiException invalid() { return new ApiException(ErrorCode.VALIDATION_FAILED,"요청 값이 올바르지 않습니다."); }
}
