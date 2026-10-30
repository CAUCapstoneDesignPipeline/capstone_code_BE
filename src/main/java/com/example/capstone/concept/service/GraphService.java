package com.example.capstone.concept.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.example.capstone.analysis.repository.AnalysisGraphRepository;
import com.example.capstone.analysis.service.AnalysisService;
import com.example.capstone.note.service.NoteService;
import com.example.capstone.topic.service.TopicService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;

@Service
@Transactional(readOnly=true)
public class GraphService {
    private final AnalysisGraphRepository repository;
    private final AnalysisService analysis;
    private final NoteService notes;
    private final TopicService topics;
    private final ObjectMapper mapper;
    public GraphService(AnalysisGraphRepository repository,AnalysisService analysis,NoteService notes,TopicService topics,ObjectMapper mapper) {
        this.repository=repository;this.analysis=analysis;this.notes=notes;this.topics=topics;this.mapper=mapper;
    }
    public ObjectNode note(UUID user,UUID note) {
        notes.requireOwned(user,note);var result=mapper.createObjectNode();result.put("noteId",note.toString());result.set("analysis",mapper.valueToTree(analysis.get(user,note).job()));
        result.set("concepts",mapper.valueToTree(concepts(user,"note",note,null,false)));result.set("relations",mapper.valueToTree(relations(user,"note",note,null,false)));return result;
    }
    public ObjectNode graph(UUID user,String scope,String noteValue,String topicValue,boolean includeLost) {
        if(!Set.of("all","topic","note").contains(scope)) throw invalid();
        UUID note=null,topic=null;boolean unassigned="none".equals(topicValue);
        if(scope.equals("note")) { note=id(noteValue);notes.requireOwned(user,note); }
        if(scope.equals("topic") && !unassigned) { topic=id(topicValue);topics.owned(user,topic); }
        var nodes=new LinkedHashMap<String,JsonNode>();var edges=mapper.createArrayNode();
        for(var c:concepts(user,scope,note,topic,unassigned)) { ObjectNode node=mapper.createObjectNode();node.set("id",c.path("id"));node.set("label",c.path("label"));nodes.put(c.path("id").asString(),node); }
        for(var r:relations(user,scope,note,topic,unassigned)) {
            if(!includeLost && r.path("status").asString().equals("evidence_lost")) continue;
            nodes.put(r.path("source").path("id").asString(),r.path("source"));nodes.put(r.path("target").path("id").asString(),r.path("target"));
            var edge=mapper.createObjectNode();edge.set("id",r.path("id"));edge.set("sourceId",r.path("source").path("id"));edge.set("targetId",r.path("target").path("id"));
            for(String key:new String[]{"type","origin","status","asHypothesis"}) edge.set(key,r.path(key));edges.add(edge);
        }
        var result=mapper.createObjectNode();result.put("scope",scope);result.set("nodes",mapper.valueToTree(nodes.values()));result.set("edges",edges);return result;
    }
    public ObjectNode relation(UUID user,UUID id) {
        for(var relation:relations(user,"all",null,null,false)) if(relation.path("id").asString().equals(id.toString())) {
            ObjectNode result=(ObjectNode)relation.deepCopy();JsonNode links=repository.detailLinks(user,id);result.set("candidateId",links.path("candidateId"));result.set("derived",links.path("derived"));return result;
        }
        throw new ApiException(ErrorCode.NOT_FOUND,"관계를 찾을 수 없습니다.");
    }
    public List<JsonNode> types() { return repository.types(); }
    private List<JsonNode> concepts(UUID u,String scope,UUID note,UUID topic,boolean unassigned) {
        var values=new ArrayList<>(repository.legacyConcepts(u,scope,note,topic,unassigned));values.addAll(repository.items(u,scope,note,topic,unassigned,"concept"));return unique(values);
    }
    private List<JsonNode> relations(UUID u,String scope,UUID note,UUID topic,boolean unassigned) {
        var values=new ArrayList<>(repository.legacyRelations(u,scope,note,topic,unassigned));values.addAll(repository.items(u,scope,note,topic,unassigned,"relation"));return unique(values);
    }
    private List<JsonNode> unique(List<JsonNode> items) {
        var unique=new LinkedHashMap<String,JsonNode>();
        for(var item:items) {
            String id=item.path("id").asString();JsonNode previous=unique.get(id);
            if(previous==null) { unique.put(id,item.deepCopy());continue; }
            ObjectNode merged=(ObjectNode)previous;
            for(String field:new String[]{"evidence","mentions"}) if(item.has(field)) {
                var rows=new LinkedHashMap<String,JsonNode>();for(var row:merged.path(field)) rows.put(row.toString(),row);for(var row:item.path(field)) rows.put(row.toString(),row);
                merged.set(field,mapper.valueToTree(rows.values()));
            }
            if(item.path("status").asString().equals("active")) merged.put("status","active");
        }
        return List.copyOf(unique.values());
    }
    public static UUID id(String value) {
        try { UUID id=UUID.fromString(value);if(id.toString().equalsIgnoreCase(value)) return id; } catch(RuntimeException ignored) { }
        throw invalid();
    }
    private static ApiException invalid() { return new ApiException(ErrorCode.VALIDATION_FAILED,"요청 값이 올바르지 않습니다."); }
}
