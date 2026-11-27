package com.example.capstone.concept.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.capstone.concept.dto.response.GraphSearchResponse;
import com.example.capstone.concept.dto.response.GraphSearchResponse.PathItem;
import com.example.capstone.concept.dto.response.GraphSearchResponse.Result;
import com.example.capstone.concept.service.TopicGraphService.Index;
import com.example.capstone.global.validation.TextValidation;
import com.example.capstone.topic.domain.Topic;

/**
 * Galaxy 그래프 검색(#144). 주제 이름과 키워드 대표 표기에서 부분 일치(대소문자 무시)로 찾는다.
 * 키워드의 경로는 주제 그래프(#143)와 같은 "바로 속한 주제" 규칙을 쓰므로, FE는 path를 따라 GET /graph/topics/{id}를 차례로 부르면 그 노드에 닿는다.
 */
@Service
@Transactional(readOnly=true)
public class GraphSearchService {
    public static final int DEFAULT_LIMIT=20;
    public static final int MAX_LIMIT=50;
    private static final PathItem UNASSIGNED=new PathItem(TopicGraphService.UNASSIGNED,"미분류");
    private final TopicGraphService graph;
    public GraphSearchService(TopicGraphService graph) { this.graph=graph; }

    public GraphSearchResponse search(UUID user,String q,Integer limit) {
        String query=q==null?"":normalize(q);
        if(query.isEmpty()) throw TextValidation.invalid("q","empty","검색어를 입력하세요.");
        int size=limit==null?DEFAULT_LIMIT:limit;
        if(size<1 || size>MAX_LIMIT) throw TextValidation.invalid("limit","range","limit은 1~"+MAX_LIMIT+" 사이여야 합니다.");
        Index index=graph.index(user);
        var topics=new ArrayList<Result>();var keywords=new ArrayList<Result>();
        for(Topic topic:index.topics()) if(matches(topic.getName(),query)) {
            List<UUID> path=index.path(topic.getId());
            topics.add(new Result("topic",topic.getId().toString(),topic.getName(),"extracted",false,items(index,path.subList(0,path.size()-1))));
        }
        for(var keyword:index.keywords()) if(matches(keyword.label(),query)) {
            keywords.add(new Result("keyword",keyword.id(),keyword.label(),keyword.origin(),keyword.confused(),keyword.home()==null?List.of(UNASSIGNED):items(index,index.path(keyword.home()))));
        }
        Comparator<Result> order=Comparator.comparing((Result result) -> !normalize(result.name()).equals(query))
                .thenComparing(result -> !normalize(result.name()).startsWith(query))
                .thenComparing(Result::name).thenComparingInt(result -> result.path().size()).thenComparing(Result::id);
        topics.sort(order);keywords.sort(order);
        var results=new ArrayList<Result>(topics);results.addAll(keywords);
        return new GraphSearchResponse(List.copyOf(results.subList(0,Math.min(size,results.size()))));
    }
    private static List<PathItem> items(Index index,List<UUID> path) { return path.stream().map(id -> new PathItem(id.toString(),index.topic(id).getName())).toList(); }
    private static boolean matches(String name,String query) { return normalize(name).contains(query); }
    private static String normalize(String value) { return Normalizer.normalize(value,Normalizer.Form.NFC).strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
}
