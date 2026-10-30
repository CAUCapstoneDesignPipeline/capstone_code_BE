package com.example.capstone.analysis.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.example.capstone.analysis.repository.AnalysisGraphRepository;

/** 유효한 사용자 소유 병합 근거만 정본 별칭에 반영한다. */
@Service
public class AnalysisAliasService {
    private final AnalysisGraphRepository repository;
    public AnalysisAliasService(AnalysisGraphRepository repository) { this.repository=repository; }
    @Transactional(readOnly=true) public List<JsonNode> aliases(UUID user) { return repository.aliases(user); }
    public static Map<String,String> mapping(List<JsonNode> rows) {
        var parent=new HashMap<String,String>();
        for(var row:rows) {
            String id=row.path("id").asString();parent.putIfAbsent(id,id);
            for(var alias:row.path("aliases")) {
                String value=alias.asString();parent.putIfAbsent(value,value);String a=root(parent,id),b=root(parent,value);
                if(a.compareTo(b)<0) parent.put(b,a);else parent.put(a,b);
            }
        }
        for(String id:parent.keySet()) root(parent,id);return parent;
    }
    private static String root(Map<String,String> parent,String id) { String p=parent.get(id);if(p.equals(id)) return id;String root=root(parent,p);parent.put(id,root);return root; }
}
