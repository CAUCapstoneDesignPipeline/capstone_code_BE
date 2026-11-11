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
    private final com.example.capstone.analysis.config.AiPipelineConfiguration configuration;
    public GraphService(AnalysisGraphRepository repository,AnalysisService analysis,NoteService notes,TopicService topics,ObjectMapper mapper,com.example.capstone.analysis.config.AiPipelineConfiguration configuration) {
        this.repository=repository;this.analysis=analysis;this.notes=notes;this.topics=topics;this.mapper=mapper;this.configuration=configuration;
    }
    public ObjectNode note(UUID user,UUID note) {
        notes.requireOwned(user,note);var result=mapper.createObjectNode();result.put("noteId",note.toString());result.set("analysis",mapper.valueToTree(analysis.get(user,note).job()));
        result.set("concepts",mapper.valueToTree(concepts(user,"note",note,null,false)));result.set("relations",mapper.valueToTree(relations(user,"note",note,null,false)));return result;
    }
    /**
     * 페이지 그래프(v0.4). 노드는 페이지, 엣지는 키워드를 하나 이상 공유하는 두 페이지 사이의 무방향 선 1개다.
     * 키워드는 기능용 그래프의 개념으로, 병합된 개념(같은 canonical id)이나 정규화한 이름이 같은 개념을 같은 키워드로 본다.
     * 공유 키워드가 없는 페이지도 노드로 주며, 응답 크기는 우선 제한하지 않는다(11/4 결정).
     */
    public ObjectNode pages(UUID user,String scope,String topicValue) {
        if(!Set.of("all","topic").contains(scope)) throw invalid();
        UUID topic=null;boolean unassigned=false;
        if(scope.equals("topic")) {
            if(topicValue==null || topicValue.isBlank()) throw invalid();
            unassigned="none".equals(topicValue);
            if(!unassigned) { topic=id(topicValue);topics.owned(user,topic); }
        }
        var nodes=repository.pages(user,scope.equals("all"),topic,unassigned);
        var included=new java.util.HashSet<String>();nodes.forEach(n->included.add(n.path("id").asString()));
        Map<String,String> aliases=com.example.capstone.analysis.service.AnalysisAliasService.mapping(repository.aliases(user));
        var keywordsByNote=new java.util.HashMap<String,java.util.Set<String>>();var labels=new java.util.TreeMap<String,String>();
        for(var row:repository.pageKeywords(user)) {
            String note=row.path("noteId").asString(),label=java.text.Normalizer.normalize(row.path("label").asString(),java.text.Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");
            if(!included.contains(note) || label.isBlank()) continue;
            String keyword="label:"+label.toLowerCase(java.util.Locale.ROOT);
            String concept=row.path("conceptId").asString();concept=aliases.getOrDefault(concept,concept);
            keywordsByNote.computeIfAbsent(note,ignored->new java.util.HashSet<>()).add(keyword);
            labels.merge(keyword,label,(a,b)->a.compareTo(b)<=0?a:b);
            keywordsByNote.get(note).add("concept:"+concept);labels.merge("concept:"+concept,label,(a,b)->a.compareTo(b)<=0?a:b);
        }
        var notes=new ArrayList<>(keywordsByNote.keySet());java.util.Collections.sort(notes);
        var edges=mapper.createArrayNode();
        for(int i=0;i<notes.size();i++) for(int j=i+1;j<notes.size();j++) {
            var shared=new java.util.TreeSet<String>();
            for(String keyword:keywordsByNote.get(notes.get(i))) if(keywordsByNote.get(notes.get(j)).contains(keyword)) shared.add(labels.get(keyword));
            if(shared.isEmpty()) continue;
            var edge=edges.addObject();edge.put("sourceId",notes.get(i));edge.put("targetId",notes.get(j));var names=edge.putArray("sharedKeywords");shared.forEach(names::add);
        }
        var result=mapper.createObjectNode();result.put("scope",scope);result.set("nodes",mapper.valueToTree(nodes));result.set("edges",edges);return result;
    }
    public ObjectNode relation(UUID user,UUID id) {
        for(var relation:relations(user,"all",null,null,false)) if(relation.path("id").asString().equals(id.toString())) {
            ObjectNode result=(ObjectNode)relation.deepCopy();JsonNode links=repository.detailLinks(user,id);result.set("candidateId",links.path("candidateId"));result.set("derived",links.path("derived"));return result;
        }
        throw new ApiException(ErrorCode.NOT_FOUND,"관계를 찾을 수 없습니다.");
    }
    public List<JsonNode> types() { var types=new LinkedHashMap<String,JsonNode>();for(var type:repository.types()) types.put(type.path("code").asString(),type);if(configuration.configured()) for(var type:configuration.policy().path("relationTypes")) types.putIfAbsent(type.path("code").asString(),type);return List.copyOf(types.values()); }
    private List<JsonNode> concepts(UUID u,String scope,UUID note,UUID topic,boolean unassigned) {
        var values=new ArrayList<>(repository.legacyConcepts(u,scope,note,topic,unassigned));values.addAll(repository.items(u,scope,note,topic,unassigned,"concept"));return unique(canonicalize(u,values));
    }
    private List<JsonNode> relations(UUID u,String scope,UUID note,UUID topic,boolean unassigned) {
        var values=new ArrayList<>(repository.legacyRelations(u,scope,note,topic,unassigned));values.addAll(repository.items(u,scope,note,topic,unassigned,"relation"));return unique(canonicalize(u,values));
    }
    private List<JsonNode> canonicalize(UUID user,List<JsonNode> values) {
        Map<String,String> aliases=com.example.capstone.analysis.service.AnalysisAliasService.mapping(repository.aliases(user));
        var result=new ArrayList<JsonNode>();
        for(var value:values) {
            ObjectNode copy=(ObjectNode)value.deepCopy();copy.remove("aliases");
            if(copy.has("source")) {
                for(String endpoint:List.of("source","target")) { ObjectNode node=(ObjectNode)copy.path(endpoint);String id=node.path("id").asString();node.put("id",aliases.getOrDefault(id,id)); }
                if(copy.path("source").path("id").equals(copy.path("target").path("id"))) continue;
            } else { String id=copy.path("id").asString();copy.put("id",aliases.getOrDefault(id,id)); }
            result.add(copy);
        }
        return result;
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
