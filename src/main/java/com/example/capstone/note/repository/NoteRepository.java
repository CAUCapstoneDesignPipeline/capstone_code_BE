package com.example.capstone.note.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.capstone.note.domain.Note;

public interface NoteRepository extends JpaRepository<Note,UUID> {
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update Note n set n.topicId = :topicId, n.updatedAt = :now where n.id = :id and n.userId = :userId")
    int move(UUID userId,UUID id,UUID topicId,Instant now);
    long countByUserIdAndTopicId(UUID userId,UUID topicId);
    Optional<Note> findByIdAndUserId(UUID id,UUID userId);
    @Query("""
            select count(n) > 0 from Note n where n.userId = :userId and n.id <> :except
            and n.title = :title and (n.topicId = :topicId or (:topicId is null and n.topicId is null))
            """)
    boolean titleExists(UUID userId,UUID topicId,String title,UUID except);
    @Query("""
            select n from Note n where n.userId = :userId
            and (:allTopics = true or (:unassigned = true and n.topicId is null) or n.topicId = :topicId)
            """)
    List<Note> list(UUID userId,boolean allTopics,boolean unassigned,UUID topicId,Sort sort);
}
