package com.example.capstone.concept.service;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.example.capstone.analysis.repository.AnalysisGraphRepository;
import com.example.capstone.analysis.service.AnalysisAliasService;
import com.example.capstone.concept.dto.response.TopicGraphResponse;
import com.example.capstone.concept.dto.response.TopicGraphResponse.Document;
import com.example.capstone.concept.dto.response.TopicGraphResponse.Edge;
import com.example.capstone.concept.dto.response.TopicGraphResponse.KeywordNode;
import com.example.capstone.concept.dto.response.TopicGraphResponse.TopicNode;
import com.example.capstone.concept.dto.response.TopicGraphResponse.Unassigned;
import com.example.capstone.topic.domain.Topic;
import com.example.capstone.topic.repository.TopicRepository;
import com.example.capstone.topic.service.TopicService;

/**
 * 주제 그래프(Galaxy, #143). 한 번에 한 단계만 준다.
 *
 * <ul>
 *   <li>키워드는 페이지 그래프와 같은 기준으로 합친다: 정규화한 이름(대소문자 무시)이 같거나 병합된 개념(같은 정본 id)이면 같은 키워드다.</li>
 *   <li>키워드가 "바로 속한 주제"는 그 키워드가 나온 노트가 가장 많은 주제 하나다(미분류도 한 곳으로 센다).
 *       같으면 주제 UUID 문자열이 작은 쪽, 미분류는 주제에 밀린다. 검색(#144)도 같은 규칙을 쓴다.</li>
 *   <li>선은 개념 사이 관계(근거를 잃은 관계 제외)를 이 단계에 보이는 노드로 옮겨 합친 것이다. 하위 주제 안쪽 키워드는
 *       그 하위 주제 노드로 대신하고, 이 단계 밖 키워드와의 관계는 그리지 않는다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly=true)
public class TopicGraphService {
    public static final String UNASSIGNED="none";
    private final AnalysisGraphRepository repository;
    private final TopicRepository topicRepository;
    private final TopicService topics;
    public TopicGraphService(AnalysisGraphRepository repository,TopicRepository topicRepository,TopicService topics) {
        this.repository=repository;this.topicRepository=topicRepository;this.topics=topics;
    }

    /** 최상위 단계: 최상위 주제와 미분류 요약. */
    public TopicGraphResponse top(UUID user) { return view(user,index(user),null,false); }

    /** 주제 안 단계. value가 "none"이면 미분류 키워드 단계다. 타인·없는 주제는 404. */
    public TopicGraphResponse topic(UUID user,String value) {
        if(UNASSIGNED.equals(value)) return view(user,index(user),null,true);
        UUID topic=GraphService.id(value);topics.owned(user,topic);
        return view(user,index(user),topic,false);
    }

    private TopicGraphResponse view(UUID user,Index index,UUID level,boolean unassigned) {
        List<Topic> children=unassigned?List.of():index.children.getOrDefault(level,List.of());
        var nodes=new HashMap<String,String>();
        for(Keyword keyword:index.keywords.values()) {
            String node=visibleNode(index,keyword,level,unassigned);
            if(node!=null) nodes.put(keyword.id,node);
        }
        var topicNodes=children.stream().map(topic -> new TopicNode(topic.getId().toString(),topic.getName(),index.subtreeKeywords(topic.getId()),
                !index.children.getOrDefault(topic.getId(),List.of()).isEmpty(),index.children.getOrDefault(topic.getId(),List.of()).size(),
                index.subtreeRecommended(topic.getId()))).toList();
        var keywordNodes=index.keywords.values().stream().filter(keyword -> keyword.id.equals(nodes.get(keyword.id)))
                .sorted(Comparator.comparing((Keyword keyword) -> keyword.label).thenComparing(keyword -> keyword.id)).map(Keyword::node).toList();
        var pairs=new TreeSet<String>();var edges=new ArrayList<Edge>();
        for(String[] relation:index.relations) {
            String a=nodes.get(relation[0]),b=nodes.get(relation[1]);
            if(a==null || b==null || a.equals(b)) continue;
            String left=a.compareTo(b)<0?a:b,right=a.compareTo(b)<0?b:a;
            if(pairs.add(left+"|"+right)) edges.add(new Edge(left,right));
        }
        edges.sort(Comparator.comparing(Edge::sourceId).thenComparing(Edge::targetId));
        Unassigned summary=null;
        if(level==null && !unassigned) {
            var none=index.keywords.values().stream().filter(keyword -> keyword.home==null).toList();
            summary=new Unassigned(none.size(),none.stream().anyMatch(Keyword::generated));
        }
        return new TopicGraphResponse(topicNodes,keywordNodes,edges,summary);
    }

    /** 이 단계에서 키워드를 대신 그리는 노드 id. 보이지 않으면 null. */
    private String visibleNode(Index index,Keyword keyword,UUID level,boolean unassigned) {
        if(unassigned) return keyword.home==null?keyword.id:null;
        if(keyword.home==null) return null;
        List<UUID> path=index.path(keyword.home);
        if(level==null) return path.isEmpty()?null:path.getFirst().toString();
        int at=path.indexOf(level);
        if(at<0) return null;
        return at==path.size()-1?keyword.id:path.get(at+1).toString();
    }

    /** 사용자의 키워드·주제 계층·관계를 한 번에 읽어 만든 색인. 검색(#144)도 이것을 쓴다. */
    public Index index(UUID user) {
        Map<String,String> aliases=AnalysisAliasService.mapping(repository.aliases(user));
        var rows=new ArrayList<Row>();var union=new HashMap<String,String>();
        for(JsonNode row:repository.topicKeywords(user)) {
            String label=Normalizer.normalize(row.path("label").asString(),Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");
            if(label.isBlank()) continue;
            String concept=row.path("conceptId").asString();concept=aliases.getOrDefault(concept,concept);
            String labelKey="label:"+label.toLowerCase(Locale.ROOT),conceptKey="concept:"+concept;
            join(union,labelKey,conceptKey);
            String topic=row.path("topicId").isNull() || row.path("topicId").isMissingNode()?null:row.path("topicId").asString();
            rows.add(new Row(row.path("noteId").asString(),row.path("title").asString(),topic==null?null:UUID.fromString(topic),concept,label,labelKey,conceptKey,
                    "generated".equals(row.path("origin").asString())));
        }
        var grouped=new LinkedHashMap<String,List<Row>>();
        for(Row row:rows) grouped.computeIfAbsent(find(union,row.labelKey),ignored -> new ArrayList<>()).add(row);
        var keywords=new LinkedHashMap<String,Keyword>();var byConcept=new HashMap<String,String>();
        for(List<Row> group:grouped.values()) {
            Keyword keyword=Keyword.of(user,group);keywords.put(keyword.id,keyword);
            for(Row row:group) byConcept.put(row.conceptKey,keyword.id);
        }
        var relations=new ArrayList<String[]>();
        var relationRows=new ArrayList<JsonNode>(repository.legacyRelations(user,"all",null,null,false));
        relationRows.addAll(repository.items(user,"all",null,null,false,"relation"));
        for(JsonNode relation:relationRows) {
            if("evidence_lost".equals(relation.path("status").asString())) continue;
            String source=relation.path("source").path("id").asString(),target=relation.path("target").path("id").asString();
            String a=byConcept.get("concept:"+aliases.getOrDefault(source,source)),b=byConcept.get("concept:"+aliases.getOrDefault(target,target));
            if(a!=null && b!=null && !a.equals(b)) relations.add(new String[]{a,b});
        }
        var all=new HashMap<UUID,Topic>();var children=new HashMap<UUID,List<Topic>>();
        for(Topic topic:topicRepository.findByUserId(user)) all.put(topic.getId(),topic);
        for(Topic topic:all.values()) children.computeIfAbsent(topic.getParentId(),ignored -> new ArrayList<>()).add(topic);
        children.values().forEach(list -> list.sort(Comparator.comparingInt(Topic::getSortOrder).thenComparing(Topic::getName).thenComparing(Topic::getId)));
        return new Index(keywords,relations,all,children);
    }

    private static void join(Map<String,String> parent,String a,String b) {
        String x=find(parent,a),y=find(parent,b);
        if(!x.equals(y)) { if(x.compareTo(y)<0) parent.put(y,x); else parent.put(x,y); }
    }
    private static String find(Map<String,String> parent,String key) {
        parent.putIfAbsent(key,key);String at=key;
        while(!parent.get(at).equals(at)) at=parent.get(at);
        parent.put(key,at);return at;
    }

    private record Row(String noteId,String title,UUID topicId,String conceptId,String label,String labelKey,String conceptKey,boolean generated) { }

    /** 합쳐진 키워드 하나. home은 바로 속한 주제(null이면 미분류). */
    public record Keyword(String id,String label,String origin,int conceptCount,UUID home,List<Document> documents) {
        boolean generated() { return "generated".equals(origin); }
        KeywordNode node() { return new KeywordNode(id,label,conceptCount,origin,home==null?null:home.toString(),documents); }
        static Keyword of(UUID user,List<Row> rows) {
            String label=rows.stream().map(Row::label).min(Comparator.naturalOrder()).orElseThrow();
            String key=rows.stream().map(Row::labelKey).min(Comparator.naturalOrder()).orElseThrow();
            String id=UUID.nameUUIDFromBytes((user+"|"+key).getBytes(StandardCharsets.UTF_8)).toString();
            String origin=rows.stream().allMatch(Row::generated)?"generated":"extracted";
            int concepts=(int)rows.stream().map(Row::conceptId).distinct().count();
            var titles=new TreeMap<String,String>();var places=new HashMap<String,Set<String>>();
            for(Row row:rows) {
                titles.putIfAbsent(row.noteId,row.title);
                places.computeIfAbsent(row.topicId==null?UNASSIGNED:row.topicId.toString(),ignored -> new HashSet<>()).add(row.noteId);
            }
            String home=places.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String,Set<String>> entry) -> -entry.getValue().size())
                    .thenComparing(entry -> UNASSIGNED.equals(entry.getKey())).thenComparing(Map.Entry::getKey)).findFirst().orElseThrow().getKey();
            var documents=titles.entrySet().stream().map(entry -> new Document(entry.getKey(),entry.getValue()))
                    .sorted(Comparator.comparing(Document::title).thenComparing(Document::noteId)).toList();
            return new Keyword(id,label,origin,concepts,UNASSIGNED.equals(home)?null:UUID.fromString(home),documents);
        }
    }

    /** 키워드와 주제 계층. children의 키 null은 최상위다. */
    public static final class Index {
        final Map<String,Keyword> keywords;
        final List<String[]> relations;
        final Map<UUID,Topic> topics;
        final Map<UUID,List<Topic>> children;
        private final Map<UUID,List<UUID>> paths=new HashMap<>();
        Index(Map<String,Keyword> keywords,List<String[]> relations,Map<UUID,Topic> topics,Map<UUID,List<Topic>> children) {
            this.keywords=keywords;this.relations=relations;this.topics=topics;this.children=children;
        }
        public java.util.Collection<Keyword> keywords() { return keywords.values(); }
        public java.util.Collection<Topic> topics() { return topics.values(); }
        public Topic topic(UUID id) { return topics.get(id); }
        /** 최상위부터 id까지의 주제 id(자기 자신 포함). 주제가 없으면 빈 목록. */
        public List<UUID> path(UUID id) {
            return paths.computeIfAbsent(id,key -> {
                var path=new ArrayList<UUID>();
                for(Topic at=topics.get(key);at!=null && path.size()<=topics.size();at=at.getParentId()==null?null:topics.get(at.getParentId())) path.addFirst(at.getId());
                return List.copyOf(path);
            });
        }
        int subtreeKeywords(UUID topic) { return (int)keywords.values().stream().filter(keyword -> keyword.home!=null && path(keyword.home).contains(topic)).count(); }
        boolean subtreeRecommended(UUID topic) {
            return keywords.values().stream().anyMatch(keyword -> keyword.generated() && keyword.home!=null && path(keyword.home).contains(topic));
        }
    }
}
