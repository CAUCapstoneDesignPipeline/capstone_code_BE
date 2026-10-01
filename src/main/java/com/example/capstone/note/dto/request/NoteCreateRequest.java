package com.example.capstone.note.dto.request;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

public class NoteCreateRequest {
    @JsonProperty private String title;
    @JsonProperty private UUID topicId;
    private String body = "";
    @JsonSetter(nulls=Nulls.FAIL)
    public void setBody(String body) { this.body=body; }
    public String title() { return title; }
    public UUID topicId() { return topicId; }
    public String body() { return body; }
}
