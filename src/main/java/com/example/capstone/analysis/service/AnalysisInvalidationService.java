package com.example.capstone.analysis.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.analysis.repository.AnalysisInvalidationRepository;
import com.example.capstone.user.repository.AppUserRepository;

@Service
public class AnalysisInvalidationService {
    private final AnalysisInvalidationRepository repository;
    private final AppUserRepository users;
    public AnalysisInvalidationService(AnalysisInvalidationRepository repository,AppUserRepository users) { this.repository=repository;this.users=users; }
    @Transactional public void policyChanged(UUID user) { users.lockById(user).orElseThrow();repository.policyChanged(user); }
    @Transactional public void noteChanged(UUID user,UUID note,boolean inputChanged) {
        users.lockById(user).orElseThrow();repository.invalidate(user,List.of(note),inputChanged);
    }
    @Transactional public void topicChanged(UUID user,UUID topic) {
        users.lockById(user).orElseThrow();repository.invalidate(user,repository.noteIds(user,topic),true);
    }
}
