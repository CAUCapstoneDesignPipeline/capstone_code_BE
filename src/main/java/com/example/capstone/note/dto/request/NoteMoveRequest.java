package com.example.capstone.note.dto.request;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record NoteMoveRequest(@JsonProperty(required=true) UUID topicId) { }
