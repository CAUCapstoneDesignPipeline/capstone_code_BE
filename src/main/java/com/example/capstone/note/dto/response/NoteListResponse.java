package com.example.capstone.note.dto.response;

import java.util.List;

public record NoteListResponse(List<NoteSummaryResponse> notes) { }
