package com.example.capstone.note.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record NoteUpdateRequest(String title,@NotNull String body,@NotNull @PositiveOrZero Integer version) { }
