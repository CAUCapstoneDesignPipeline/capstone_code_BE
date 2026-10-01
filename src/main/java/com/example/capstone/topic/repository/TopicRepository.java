package com.example.capstone.topic.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.capstone.topic.domain.Topic;
import com.example.capstone.topic.dto.response.TopicResponse;

public interface TopicRepository extends JpaRepository<Topic,UUID> {
    Optional<Topic> findByIdAndUserId(UUID id,UUID userId);
    boolean existsByUserIdAndNameAndIdNot(UUID userId,String name,UUID id);
    @Query("select coalesce(max(t.sortOrder), -1) from Topic t where t.userId = :userId")
    int maxOrder(UUID userId);
    @Query("""
            select new com.example.capstone.topic.dto.response.TopicResponse(t.id,t.name,t.sortOrder,count(n),t.createdAt)
            from Topic t left join Note n on n.topicId = t.id and n.userId = :userId
            where t.userId = :userId group by t.id,t.name,t.sortOrder,t.createdAt order by t.sortOrder,t.name
            """)
    List<TopicResponse> list(UUID userId);
}
