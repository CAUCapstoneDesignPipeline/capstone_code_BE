package com.example.capstone.analysis.dto.response;

import java.util.UUID;

/** 지운 행 수. 노트와 주제는 지우지 않는다. */
public record DeveloperAnalysisResetResponse(UUID userId, int jobs, int results, int graphItems, int feedback) {
}
