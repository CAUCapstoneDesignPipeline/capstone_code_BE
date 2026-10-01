package com.example.capstone.note.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.capstone.note.domain.Note;

public interface NoteRepository extends JpaRepository<Note,UUID> {
    long countByUserIdAndTopicId(UUID userId,UUID topicId);
}
