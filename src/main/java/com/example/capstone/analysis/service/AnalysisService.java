package com.example.capstone.analysis.service;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.analysis.dto.response.NoteAnalysisResponse;
import com.example.capstone.analysis.dto.response.AnalysisJobResponse;
import com.example.capstone.analysis.repository.AnalysisJobRepository;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import com.example.capstone.note.service.NoteService;

@Service
public class AnalysisService {
    private final NoteService notes;
    private final AnalysisJobRepository jobs;
    private final AnalysisWorkService work;
    private final AiActivationService activation;

    public AnalysisService(NoteService notes, AnalysisJobRepository jobs,AnalysisWorkService work,AiActivationService activation) {
        this.notes = notes;
        this.jobs = jobs;
        this.work=work;this.activation=activation;
    }

    @Transactional(readOnly=true)
    public NoteAnalysisResponse get(UUID userId, UUID noteId) {
        notes.requireOwned(userId, noteId);
        return new NoteAnalysisResponse(jobs.latest(userId, noteId)
                .map(AnalysisJobResponse::from)
                .orElse(null));
    }

    public AnalysisJobResponse request(UUID userId,UUID noteId) {
        notes.requireOwned(userId,noteId);
        activation.requireAvailable();
        try { return AnalysisJobResponse.from(work.submit(userId,noteId,activation.policyFingerprint())); }
        catch(ApiException failure) {
            // afterCompletion 콜백 오류를 Spring이 삼켜도 정상 off 응답 전에 동기 확인한다.
            if(failure.errorCode()==ErrorCode.AI_UNAVAILABLE)activation.flushRevocation();
            throw failure;
        }
    }
}
