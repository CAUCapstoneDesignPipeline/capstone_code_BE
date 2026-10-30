package com.example.capstone.analysis.service;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.example.capstone.analysis.repository.AnalysisWorkRepository;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.worker.AnalysisPipeline.Work;
import com.example.capstone.user.repository.AppUserRepository;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class AnalysisWorkService {
    private final AnalysisWorkRepository repository;
    private final AppUserRepository users;
    private final AnalysisInvalidationService invalidation;
    private final com.example.capstone.analysis.repository.AnalysisResultRepository results;
    public AnalysisWorkService(AnalysisWorkRepository repository,AppUserRepository users,com.example.capstone.analysis.repository.AnalysisResultRepository results,AnalysisInvalidationService invalidation) { this.repository=repository;this.users=users;this.results=results;this.invalidation=invalidation; }
    @Transactional
    public JsonNode submit(UUID user,UUID note) { return submit(user,note,null); }
    @Transactional
    public JsonNode submit(UUID user,UUID note,String policyDigest) {
        users.lockById(user).orElseThrow(()->new ApiException(ErrorCode.UNAUTHENTICATED,"로그인이 필요합니다."));
        String extractor=policyDigest==null?"ai-integration-2026-10-30-adopted.1":"policy:"+policyDigest;
        String previous=repository.latestPolicy(user);
        if(policyDigest!=null && previous!=null && !previous.equals(extractor)) invalidation.policyChanged(user);
        repository.retireUnresumable(user,note);
        JsonNode active=repository.active(user,note); if(active!=null) return active;
        List<JsonNode> documents=repository.documents(user,note);
        JsonNode primary=documents.stream().filter(n->n.path("noteId").asString().equals(note.toString())).findFirst()
                .orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"삭제된 노트입니다"));
        String body=primary.path("body").asString();
        if(body.codePointCount(0,body.length())>50000) throw new ApiException(ErrorCode.VALIDATION_FAILED,"분석할 노트는 50,000자 이하여야 합니다.");
        UUID job=UUID.randomUUID();
        repository.create(job,user,note,primary.path("noteVersion").asInt(),primary.path("inputRevision").asLong(),repository.graphRevision(user),Instant.now().plusSeconds(900),extractor);
        documents.forEach(doc->repository.snapshot(job,user,doc)); return repository.job(user,job);
    }
    @Transactional
    public Work claim() {
        UUID token=UUID.randomUUID(); JsonNode job=repository.claim(token); if(job==null) return null;
        UUID id=UUID.fromString(job.path("id").asString()),user=UUID.fromString(job.path("user_id").asString());
        return new Work(id,user,UUID.fromString(job.path("note_id").asString()),token,job.path("graph_revision").asLong(),
                Instant.parse(job.path("deadline").asString()),repository.snapshots(user,id),job.path("extractor").asString());
    }
    @Transactional public boolean heartbeat(Work work) { return repository.heartbeat(work.id(),work.leaseToken()); }
    @Transactional
    public boolean current(Work work) { return current(work,null); }
    @Transactional
    public boolean current(Work work,String policyFingerprint) {
        users.lockById(work.userId()).orElseThrow();
        if(!repository.lockValid(work.userId(),work.id(),work.leaseToken())) return false;
        if(policyFingerprint!=null && !("policy:"+policyFingerprint).equals(work.inputPolicy())) { invalidation.policyChanged(work.userId());return false; }
        if(!repository.inputsCurrent(work.userId(),work.id())) { repository.finish(work.id(),work.leaseToken(),"superseded","STALE_INPUT");return false; }
        return true;
    }
    @Transactional public void fail(Work work,String reason) {
        if(repository.lockValid(work.userId(),work.id(),work.leaseToken())) repository.finish(work.id(),work.leaseToken(),"failed",reason);
    }
    @Transactional(readOnly=true) public JsonNode checkpoint(Work work,String key,String hash) { return repository.checkpoint(work.id(),key,hash); }
    @Transactional public void reserveAttempt(Work work,String key,String operation,JsonNode request) {
        users.lockById(work.userId()).orElseThrow();
        if(!repository.lockValid(work.userId(),work.id(),work.leaseToken()) || !repository.inputsCurrent(work.userId(),work.id())) throw new LeaseLostException();
        if(!repository.reserve(UUID.fromString(request.path("context").path("stageExecutionId").asString()),work.userId(),work.id(),key,operation,AiResponseValidator.hash(request.toString())))
            throw new com.example.capstone.analysis.client.AiCallException(com.example.capstone.analysis.client.AiCallException.Reason.UNAVAILABLE);
    }
    @Transactional public void checkpoint(Work work,String key,String operation,JsonNode request,AiHttpClient.CallResult result) {
        users.lockById(work.userId()).orElseThrow();
        if(!repository.lockValid(work.userId(),work.id(),work.leaseToken()) || !repository.inputsCurrent(work.userId(),work.id())) throw new LeaseLostException();
        repository.checkpoint(UUID.fromString(request.path("context").path("stageExecutionId").asString()),work.userId(),work.id(),key,operation,
                AiResponseValidator.hash(request.toString()),result.body(),result.attempts(),result.durationMs());
        if(!repository.lockValid(work.userId(),work.id(),work.leaseToken())) throw new LeaseLostException();
    }
    @Transactional public void complete(Work work,com.example.capstone.analysis.worker.AnalysisPipeline.PipelineOutput output) {
        users.lockById(work.userId()).orElseThrow();
        if(!repository.lockValid(work.userId(),work.id(),work.leaseToken())) throw new LeaseLostException();
        if(!repository.inputsCurrent(work.userId(),work.id())) { repository.finish(work.id(),work.leaseToken(),"superseded","STALE_INPUT");return; }
        results.publish(work,output);
        if(!repository.inputsCurrent(work.userId(),work.id()) || !repository.finish(work.id(),work.leaseToken(),"succeeded",null)) throw new LeaseLostException();
    }
    public static final class LeaseLostException extends RuntimeException { public LeaseLostException() { super("Analysis lease or input changed"); } }
}
