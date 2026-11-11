package com.example.capstone.analysis.repository;

import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 단일 행만 갱신한다. 공개 조회에서 사용자·작업 전체를 순회하지 않는다. */
@Repository
public class AiRuntimeFenceRepository {
    private final AnalysisStore db;
    public AiRuntimeFenceRepository(AnalysisStore db) { this.db=db; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void disable() { db.write("update analysis_runtime_fence set epoch=epoch+1 where singleton",Map.of()); }
}
