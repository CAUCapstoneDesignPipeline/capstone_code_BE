package com.example.capstone.note.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record NoteListResponse(
        @Schema(description = "필터·정렬·검색 결과. 결과가 없으면 빈 배열, 전체 body는 포함하지 않습니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<NoteSummaryResponse> notes) { }
