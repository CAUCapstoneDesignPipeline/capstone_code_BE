package com.example.capstone.analysis.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import com.example.capstone.analysis.domain.AnalysisJob;

/** Reads existing V1 history without exposing persistence mutation methods. */
public interface AnalysisJobRepository extends Repository<AnalysisJob, UUID> {
    @Query(value = """
            select j.id, j.note_id, j.note_version, j.status, j.created_at, j.started_at, j.finished_at
            from analysis_job j join note n on n.id = j.note_id
            where j.note_id = :noteId and n.user_id = :userId
            order by j.created_at desc, j.id desc limit 1
            """, nativeQuery = true)
    Optional<AnalysisJob> latest(@Param("userId") UUID userId, @Param("noteId") UUID noteId);
}
