package com.example.capstone.note.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "note")
@DynamicUpdate
public class Note {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "topic_id") private UUID topicId;
    @Column(nullable = false, columnDefinition = "text") private String title;
    @Column(nullable = false, columnDefinition = "text") private String body;
    @Version @Column(nullable = false) private int version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Note() { }
    public Note(UUID userId,UUID topicId,String title,String body,Instant now) {
        this.id=UUID.randomUUID(); this.userId=userId; this.topicId=topicId; this.title=title;
        this.body=body; this.createdAt=now; this.updatedAt=now;
    }
    public void edit(String title,String body,Instant now) {
        this.title=title; this.body=body;
        // Even a same-content save is a versioned operation.
        this.updatedAt=now.isAfter(updatedAt)?now:updatedAt.plusNanos(1000);
    }
    public UUID getId() { return id; }
    public UUID getTopicId() { return topicId; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
