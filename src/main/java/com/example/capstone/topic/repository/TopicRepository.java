package com.example.capstone.topic.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.example.capstone.topic.domain.Topic;
import com.example.capstone.topic.dto.response.TopicResponse;

public interface TopicRepository extends JpaRepository<Topic,UUID> {
    @Modifying(flushAutomatically=true,clearAutomatically=true)
    @Query("delete from Topic t where t.userId = :userId and t.id = :id")
    int remove(UUID userId,UUID id);
    List<Topic> findByUserId(UUID userId);
    /** 현재 트랜잭션에서만 같은 부모 안 이름 중복 검사를 커밋 시점으로 미룬다(PostgreSQL SET CONSTRAINTS). */
    @Modifying
    @Query(value="set constraints topic_user_parent_name_unique deferred",nativeQuery=true)
    void deferNameCheck();
    Optional<Topic> findByIdAndUserId(UUID id,UUID userId);
    @Query("select t from Topic t where t.userId = :userId and (t.parentId = :parentId or (:parentId is null and t.parentId is null)) order by t.sortOrder,t.name")
    List<Topic> children(UUID userId,UUID parentId);
    @Query("""
            select count(t) > 0 from Topic t where t.userId = :userId and t.id <> :except and t.name = :name
            and (t.parentId = :parentId or (:parentId is null and t.parentId is null))
            """)
    boolean nameExists(UUID userId,UUID parentId,String name,UUID except);
    @Query("select coalesce(max(t.sortOrder), -1) from Topic t where t.userId = :userId and (t.parentId = :parentId or (:parentId is null and t.parentId is null))")
    int maxOrder(UUID userId,UUID parentId);
    @Query("""
            select new com.example.capstone.topic.dto.response.TopicResponse(t.id,t.parentId,t.name,t.sortOrder,count(n),t.createdAt)
            from Topic t left join Note n on n.topicId = t.id and n.userId = :userId
            where t.userId = :userId group by t.id,t.parentId,t.name,t.sortOrder,t.createdAt order by t.sortOrder,t.name,t.id
            """)
    List<TopicResponse> list(UUID userId);
}
