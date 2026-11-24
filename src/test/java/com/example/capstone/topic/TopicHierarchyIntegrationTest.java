package com.example.capstone.topic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.note.domain.Note;
import com.example.capstone.note.repository.NoteRepository;
import com.example.capstone.support.ApiIntegrationSupport;
import com.example.capstone.topic.repository.TopicRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TopicHierarchyIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired private NoteRepository notes;
    @Autowired private TopicRepository topics;

    private UUID child(String name,UUID parent) throws Exception {
        var body=new java.util.HashMap<String,Object>(); body.put("name",name); body.put("parentId",parent);
        return UUID.fromString(json(call("POST","/api/topics",mapper.writeValueAsString(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parentId").value(parent==null?null:parent.toString()))).path("id").asString());
    }
    private String parent(UUID parent) { return parent==null?"{\"parentId\":null}":"{\"parentId\":\""+parent+"\"}"; }

    @Test
    void createsUnlimitedDepthAndScopesNamesToParent() throws Exception {
        UUID root=topic("네트워크");
        UUID at=root;
        for(int depth=0;depth<6;depth++) at=child("단계"+depth,at);
        assertThat(topics.findById(at).orElseThrow().getParentId()).isNotNull();
        UUID other=topic("운영체제");
        child("공통",root); child("공통",other);
        call("POST","/api/topics",mapper.writeValueAsString(Map.of("name","공통","parentId",root)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TOPIC_NAME_TAKEN"));
        call("POST","/api/topics",mapper.writeValueAsString(Map.of("name","공통")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.parentId").isEmpty());
        UUID foreign=UUID.fromString(json(call("POST","/api/topics","{\"name\":\"타인\"}",otherBearer)).path("id").asString());
        call("POST","/api/topics",mapper.writeValueAsString(Map.of("name","x","parentId",foreign))).andExpect(status().isNotFound());
        UUID sub=child("하위",root);
        call("PATCH","/api/topics/"+sub,"{\"name\":\"공통\"}").andExpect(status().isConflict());
        call("PATCH","/api/topics/"+sub,"{\"name\":\"단계0\"}").andExpect(status().isConflict());
        call("PATCH","/api/topics/"+sub,"{\"name\":\"네트워크\"}").andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(root.toString()));
        var list=json(call("GET","/api/topics",null));
        assertThat(list.path("topics").size()).isEqualTo(12);
    }

    @Test
    void movesRejectingCyclesAndForeignParents() throws Exception {
        UUID a=topic("a"),b=child("b",a),c=child("c",b),d=topic("d");
        call("PUT","/api/topics/"+a+"/parent",parent(a)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("cycle"));
        call("PUT","/api/topics/"+a+"/parent",parent(c)).andExpect(status().isBadRequest());
        UUID foreign=UUID.fromString(json(call("POST","/api/topics","{\"name\":\"타인\"}",otherBearer)).path("id").asString());
        call("PUT","/api/topics/"+a+"/parent",parent(foreign)).andExpect(status().isNotFound());
        call("PUT","/api/topics/"+foreign+"/parent",parent(null)).andExpect(status().isNotFound());
        call("PUT","/api/topics/"+b+"/parent",parent(d)).andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(d.toString())).andExpect(jsonPath("$.sortOrder").value(0));
        assertThat(topics.findById(c).orElseThrow().getParentId()).isEqualTo(b);
        child("b",a);
        call("PUT","/api/topics/"+b+"/parent",parent(a)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TOPIC_NAME_TAKEN"));
        call("PUT","/api/topics/"+c+"/parent",parent(null)).andExpect(status().isOk()).andExpect(jsonPath("$.sortOrder").value(2));
        call("PUT","/api/topics/"+c+"/parent",parent(null)).andExpect(status().isOk()).andExpect(jsonPath("$.sortOrder").value(2));
    }

    @Test
    void reordersWithinOneParent() throws Exception {
        UUID root=topic("root"),x=child("x",root),y=child("y",root),z=child("z",root);
        topic("other");
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("parentId",root,"topicIds",List.of(z,x,y)))).andExpect(status().isOk());
        assertThat(List.of(z,x,y).stream().map(id -> topics.findById(id).orElseThrow().getSortOrder()).toList()).containsExactly(0,1,2);
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("parentId",root,"topicIds",List.of(z,x))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TOPIC_ORDER_CONFLICT"));
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("topicIds",List.of(z,x,y)))).andExpect(status().isConflict());
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("parentId",UUID.randomUUID(),"topicIds",List.of()))).andExpect(status().isConflict());
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("parentId",x,"topicIds",List.of()))).andExpect(status().isOk());
    }

    @Test
    void deleteLiftsChildrenAndNotesOneLevel() throws Exception {
        UUID root=topic("root"),mid=child("mid",root),leaf=child("leaf",mid),same=child("mid",mid);
        child("sibling",root);
        notes.saveAndFlush(new Note(userId,mid,"중간 노트","",Instant.now()));
        notes.saveAndFlush(new Note(userId,leaf,"잎 노트","",Instant.now()));
        call("DELETE","/api/topics/"+mid,null).andExpect(status().isNoContent());
        assertThat(topics.findById(leaf).orElseThrow().getParentId()).isEqualTo(root);
        assertThat(topics.findById(same).orElseThrow().getParentId()).isEqualTo(root);
        assertThat(notes.countByUserIdAndTopicId(userId,root)).isEqualTo(1);
        assertThat(notes.countByUserIdAndTopicId(userId,leaf)).isEqualTo(1);
        assertThat(topics.children(userId,root).stream().map(t -> t.getSortOrder()).toList()).containsExactly(0,1,2);
        call("DELETE","/api/topics/"+root,null).andExpect(status().isNoContent());
        assertThat(topics.children(userId,null)).extracting(t -> t.getName()).containsExactly("sibling","leaf","mid");
        assertThat(notes.countByUserIdAndTopicId(userId,null)).isEqualTo(1);
    }

    @Test
    void deleteConflictsLeaveEverythingUnchanged() throws Exception {
        UUID root=topic("root"),mid=child("mid",root);
        child("dup",root); child("dup",mid);
        call("DELETE","/api/topics/"+mid,null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOPIC_NAME_TAKEN")).andExpect(jsonPath("$.error.details.names[0]").value("dup"));
        UUID other=topic("other"),inner=child("inner",other);
        notes.saveAndFlush(new Note(userId,other,"같은 제목","",Instant.now()));
        notes.saveAndFlush(new Note(userId,inner,"같은 제목","",Instant.now()));
        call("DELETE","/api/topics/"+inner,null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_TITLE_TAKEN")).andExpect(jsonPath("$.error.details.titles[0]").value("같은 제목"));
        assertThat(topics.findById(mid)).isPresent();
        assertThat(topics.findById(inner)).isPresent();
        assertThat(notes.countByUserIdAndTopicId(userId,inner)).isEqualTo(1);
    }

    @Test
    void migrationKeepsExistingTopicsAtTopLevel() {
        assertThat(jdbc.queryForObject("select count(*) from topic where parent_id is not null",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where constraint_name='topic_user_parent_name_unique'",Long.class)).isEqualTo(1);
    }
}
