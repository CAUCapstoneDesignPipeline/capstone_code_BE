package com.example.capstone.note.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import com.example.capstone.note.dto.request.NoteUpdateRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.capstone.note.dto.request.NoteCreateRequest;
import com.example.capstone.note.dto.response.NoteResponse;
import com.example.capstone.note.dto.response.NoteListResponse;
import com.example.capstone.note.service.NoteService;

@RestController
public class NoteController {
    private final NoteService notes;
    public NoteController(NoteService notes) { this.notes=notes; }
    @PostMapping("/api/notes")
    public ResponseEntity<NoteResponse> create(@AuthenticationPrincipal Jwt jwt,@RequestBody NoteCreateRequest request) {
        return ResponseEntity.status(201).body(notes.create(UUID.fromString(jwt.getSubject()),request));
    }
    @PutMapping("/api/notes/{id}")
    public NoteResponse update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody NoteUpdateRequest request) {
        return notes.update(UUID.fromString(jwt.getSubject()),id,request);
    }
    @GetMapping("/api/notes/{id}")
    public NoteResponse get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return notes.get(UUID.fromString(jwt.getSubject()),id); }
    @GetMapping("/api/notes")
    public NoteListResponse list(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) String topicId,
            @RequestParam(defaultValue="title") String sort,@RequestParam(required=false) String q) {
        return notes.list(UUID.fromString(jwt.getSubject()),topicId,sort,q);
    }
}
