package com.example.capstone.candidate.dto.response;
import java.util.List;
public record InsightListResponse(List<InsightResponse> results,boolean unsearchedOutsideScope) { }
