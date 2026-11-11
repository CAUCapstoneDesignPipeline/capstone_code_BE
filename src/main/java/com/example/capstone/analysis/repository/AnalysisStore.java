package com.example.capstone.analysis.repository;

import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL JSON projections and lease/CAS writes which JPQL cannot express.
 * SQL is supplied only by fixed application queries, values always bound. No JDBC path. */
@Repository
public class AnalysisStore {
    private final EntityManager entityManager;
    private final ObjectMapper mapper;
    public AnalysisStore(EntityManager entityManager, ObjectMapper mapper) { this.entityManager=entityManager; this.mapper=mapper; }
    int write(String sql, Map<String,?> parameters) { return query(sql,parameters).executeUpdate(); }
    List<JsonNode> rows(String sql, Map<String,?> parameters) {
        return query(sql,parameters).getResultList().stream().map(value -> mapper.readTree(value.toString())).toList();
    }
    JsonNode one(String sql, Map<String,?> parameters) {
        List<JsonNode> rows=rows(sql,parameters); return rows.isEmpty()?null:rows.getFirst();
    }
    private Query query(String sql, Map<String,?> parameters) {
        Query query=entityManager.createNativeQuery(sql); parameters.forEach(query::setParameter); return query;
    }
    public String json(Object value) { return mapper.writeValueAsString(value); }
}
