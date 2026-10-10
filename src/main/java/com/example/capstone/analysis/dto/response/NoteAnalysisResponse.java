package com.example.capstone.analysis.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record NoteAnalysisResponse(@Schema(nullable = true) AnalysisJobResponse job) {
}
