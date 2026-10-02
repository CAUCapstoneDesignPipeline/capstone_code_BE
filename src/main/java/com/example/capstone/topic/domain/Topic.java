package com.example.capstone.topic.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "topic")
public class Topic {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false, columnDefinition = "text") private String name;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected Topic() { }
    public Topic(UUID userId,String name,int sortOrder,Instant now) {
        this.id=UUID.randomUUID(); this.userId=userId; this.name=name; this.sortOrder=sortOrder; this.createdAt=now;
    }
    public void rename(String name) { this.name=name; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
