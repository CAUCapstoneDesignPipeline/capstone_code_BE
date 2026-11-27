package com.example.capstone.analysis.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisJobListResponse;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisJobResponse;
import com.example.capstone.analysis.dto.response.DeveloperAnalysisJobSummaryResponse;
import com.example.capstone.analysis.repository.DeveloperAnalysisJobRepository;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

/** 개발자 대시보드의 분석 작업 기록 조회. 권한 확인은 컨트롤러가 먼저 한다. */
@Service
public class DeveloperAnalysisJobService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    private static final Set<String> STATUSES = Set.of("pending", "running", "succeeded", "failed");

    private final DeveloperAnalysisJobRepository repository;
    private final ObjectMapper mapper;

    public DeveloperAnalysisJobService(DeveloperAnalysisJobRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public DeveloperAnalysisJobListResponse list(String status, String from, String to, String limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status");
        }
        Instant start = instant(from, "from");
        Instant end = instant(to, "to");
        if (start != null && end != null && !start.isBefore(end)) {
            throw invalid("to");
        }
        int size = limit(limit);
        return new DeveloperAnalysisJobListResponse(repository.list(status, start, end, size).stream()
                .map(row -> mapper.treeToValue(row, DeveloperAnalysisJobSummaryResponse.class)).toList());
    }

    @Transactional(readOnly = true)
    public DeveloperAnalysisJobResponse get(String jobId) {
        UUID id = uuid(jobId);
        var job = repository.job(id);
        if (job == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "분석 작업을 찾을 수 없습니다.");
        }
        ObjectNode detail = (ObjectNode) job;
        detail.set("stages", mapper.valueToTree(repository.stages(id)));
        detail.set("inputs", mapper.valueToTree(repository.inputs(id)));
        detail.set("results", repository.results(id));
        return mapper.treeToValue(detail, DeveloperAnalysisJobResponse.class);
    }

    private static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int size = Integer.parseInt(value);
            if (size >= 1 && size <= MAX_LIMIT) {
                return size;
            }
        } catch (NumberFormatException exception) {
            // 아래에서 같은 오류로 답한다.
        }
        throw invalid("limit");
    }

    private static Instant instant(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(field);
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException exception) {
            // 경로 값이나 파서 메시지를 응답에 넣지 않는다.
        }
        throw invalid("jobId");
    }

    private static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "요청 값이 올바르지 않습니다.",
                java.util.Map.of("fields", java.util.List.of(java.util.Map.of("field", field, "reason", "invalid"))));
    }
}
