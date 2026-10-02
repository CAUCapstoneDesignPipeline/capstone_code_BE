package com.example.capstone.note.dto.response;

import java.time.Instant;
import java.util.UUID;
import com.example.capstone.note.domain.Note;

public record NoteResponse(UUID id,UUID topicId,String title,String body,int version,Instant createdAt,Instant updatedAt) {
    public static NoteResponse from(Note note) {
        return new NoteResponse(note.getId(),note.getTopicId(),note.getTitle(),note.getBody(),note.getVersion(),note.getCreatedAt(),note.getUpdatedAt());
    }
}
