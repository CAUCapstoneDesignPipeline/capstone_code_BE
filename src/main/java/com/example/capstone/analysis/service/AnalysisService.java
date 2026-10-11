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
@Transactional(readOnly = true)
public class AnalysisService {
    private final NoteService notes;
    private final AnalysisJobRepository jobs;

    public AnalysisService(NoteService notes, AnalysisJobRepository jobs) {
        this.notes = notes;
        this.jobs = jobs;
    }

    public NoteAnalysisResponse get(UUID userId, UUID noteId) {
        notes.requireOwned(userId, noteId);
        return new NoteAnalysisResponse(jobs.latest(userId, noteId)
                .map(job -> new AnalysisJobResponse(job.getId(), job.getNoteId(), job.getNoteVersion(),
                        job.getStatus(), job.getCreatedAt(), job.getStartedAt(), job.getFinishedAt()))
                .orElse(null));
    }

    public void request(UUID userId, UUID noteId) {
        notes.requireOwned(userId, noteId);
        throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 분석은 아직 사용할 수 없습니다.",
                Map.of("reason", "NOT_DEPLOYED"));
    }
}
