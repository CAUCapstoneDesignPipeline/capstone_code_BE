package com.example.capstone.analysis.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisResetResponse;
import com.example.capstone.analysis.repository.DeveloperAnalysisResetRepository;
import com.example.capstone.auth.service.DeveloperAccessService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
public class DeveloperAnalysisResetService {
    private final DeveloperAccessService access;
    private final DeveloperAnalysisResetRepository repository;

    public DeveloperAnalysisResetService(DeveloperAccessService access, DeveloperAnalysisResetRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /** 진행 중인 작업도 함께 지운다. 그 작업을 잡고 있던 worker는 lease 확인에서 멈춘다. */
    @Transactional
    public DeveloperAnalysisResetResponse reset(UUID developerId, UUID targetUserId) {
        access.require(developerId);
        if (!repository.lockUser(targetUserId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "없는 사용자입니다.");
        }
        var counts = repository.reset(targetUserId);
        return new DeveloperAnalysisResetResponse(targetUserId, counts.jobs(), counts.results(), counts.graphItems(),
                counts.feedback());
    }
}
