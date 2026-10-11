package com.example.capstone.note.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.capstone.note.domain.Note;

public interface NoteRepository extends JpaRepository<Note,UUID> {
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update Note n set n.topicId = :topicId, n.updatedAt = :now where n.id = :id and n.userId = :userId")
    int move(UUID userId,UUID id,UUID topicId,Instant now);
    // PostgreSQL FOR UPDATE blocks FK key-share locks from concurrent evidence insertion.
    @Query(value="select n.* from note n where n.id = :id and n.user_id = :userId for update",nativeQuery=true)
    Optional<Note> lockForDelete(UUID userId,UUID id);
    @Query(value="""
            select exists(select 1 from evidence_span e join note n on n.id = e.note_id
            where n.id = :id and n.user_id = :userId)
            """,nativeQuery=true)
    boolean hasEvidence(UUID userId,UUID id);
    @Query("""
            select n.title from Note n where n.userId = :userId and n.topicId = :topicId
            and exists(select 1 from Note u where u.userId = :userId and u.topicId is null and u.title = n.title)
            order by n.title
            """)
    List<String> unassignedConflicts(UUID userId,UUID topicId);
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("update Note n set n.topicId = null, n.updatedAt = :now where n.userId = :userId and n.topicId = :topicId")
    int unassignTopic(UUID userId,UUID topicId,Instant now);
    long countByUserIdAndTopicId(UUID userId,UUID topicId);
    Optional<Note> findByIdAndUserId(UUID id,UUID userId);
    boolean existsByIdAndUserId(UUID id,UUID userId);
    @Query("""
            select count(n) > 0 from Note n where n.userId = :userId and n.id <> :except
            and n.title = :title and (n.topicId = :topicId or (:topicId is null and n.topicId is null))
            """)
    boolean titleExists(UUID userId,UUID topicId,String title,UUID except);
    // PostgreSQL ILIKE supplies case-insensitive literal substring search; all inputs are bound.
    @Query(value="""
            select n.* from note n where n.user_id = :userId
            and (:allTopics = true or (:unassigned = true and n.topic_id is null) or n.topic_id = cast(:topicId as uuid))
            and (cast(:pattern as text) is null or n.title ilike cast(:pattern as text) escape chr(92)
                 or n.body ilike cast(:pattern as text) escape chr(92))
            order by case when :sort = 'title' then n.title end asc,
                     case when :sort = 'updated' then n.updated_at end desc, n.id
            """,nativeQuery=true)
    List<Note> list(UUID userId,boolean allTopics,boolean unassigned,UUID topicId,String pattern,String sort);
}
