package com.example.capstone.conversion.controller;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import tools.jackson.databind.JsonNode;
import com.example.capstone.conversion.service.*;

@RestController
@SecurityRequirement(name="bearerAuth")
public class ConversionController {
    private final ConversionService service;private final ConversionCleanup cleanup;
    public ConversionController(ConversionService service,ConversionCleanup cleanup) { this.service=service;this.cleanup=cleanup; }
    private UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    @PostMapping(value="/api/source-files",consumes="multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<JsonNode> upload(@AuthenticationPrincipal Jwt jwt,MultipartHttpServletRequest request) throws Exception {
        if(request.getMultiFileMap().size()!=1 || !request.getMultiFileMap().containsKey("file") || request.getMultiFileMap().get("file").size()!=1 || !request.getParameterMap().isEmpty())throw ConversionErrors.invalid();
        var parts=request.getParts();if(!parts.isEmpty() && (parts.size()!=1 || parts.stream().anyMatch(p->!p.getName().equals("file"))))throw ConversionErrors.invalid();
        var file=request.getFile("file");if(file==null)throw ConversionErrors.invalid();if(file.getSize()>PrivateSourceStorage.MAX_BYTES)throw ConversionErrors.limit();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.upload(user(jwt),file.getInputStream(),file.getContentType()));
    }
    @GetMapping("/api/source-files/{fileId}") public ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID fileId) {
        var value=service.download(user(jwt),fileId);String suffix=switch(value.media()){case "image/png"->"png";case "image/jpeg"->"jpg";default->"pdf";};
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"source."+suffix+"\"").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(value.media())).contentLength(value.bytes().length).body(value.bytes());
    }
    @DeleteMapping("/api/source-files/{fileId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteSource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID fileId) { service.deleteSource(user(jwt),fileId);cleanup.deletePending();return ResponseEntity.noContent().build(); }
    @PostMapping("/api/conversions") @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<JsonNode> start(@AuthenticationPrincipal Jwt jwt,@RequestBody JsonNode request) { return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.start(user(jwt),request)); }
    @GetMapping("/api/conversions/{conversionId}") public JsonNode get(@AuthenticationPrincipal Jwt jwt,@PathVariable("conversionId") UUID id) { return service.get(user(jwt),id); }
    @DeleteMapping("/api/conversions/{conversionId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable("conversionId") UUID id) { service.cancel(user(jwt),id);return ResponseEntity.noContent().build(); }
    @GetMapping("/api/conversions/{conversionId}/result") public JsonNode result(@AuthenticationPrincipal Jwt jwt,@PathVariable("conversionId") UUID id) { return service.result(user(jwt),id); }
    @PostMapping("/api/conversions/{conversionId}/confirm") @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<JsonNode> confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable("conversionId") UUID id,@RequestBody JsonNode request) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.confirm(user(jwt),id,request)); }
}
