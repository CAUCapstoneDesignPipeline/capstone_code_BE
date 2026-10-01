package com.example.capstone.topic;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.note.domain.Note;
import com.example.capstone.note.repository.NoteRepository;
import com.example.capstone.topic.domain.Topic;
import com.example.capstone.topic.repository.TopicRepository;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TopicBasicsIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired private NoteRepository notes;
    @Autowired private TopicRepository topics;
    @Test
    void createsListsRenamesAndCountsOnlyOwnedNotes() throws Exception {
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics").isEmpty()).andExpect(jsonPath("$.unassignedNoteCount").value(0));
        UUID id=topic("  e\u0301  "); UUID second=topic("db"); topic("DB");
        notes.saveAndFlush(new Note(userId,id,"첫 노트","내용",Instant.now()));
        notes.saveAndFlush(new Note(userId,null,"미분류","",Instant.now()));
        notes.saveAndFlush(new Note(otherId,null,"타인","",Instant.now()));
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics[0].name").value("é"))
                .andExpect(jsonPath("$.topics[0].sortOrder").value(0)).andExpect(jsonPath("$.topics[1].sortOrder").value(1))
                .andExpect(jsonPath("$.topics[0].noteCount").value(1)).andExpect(jsonPath("$.unassignedNoteCount").value(1));
        call("PATCH","/api/topics/"+id,"{\"name\":\"새 이름\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.noteCount").value(1));
        call("PATCH","/api/topics/"+id,"{\"name\":\"db\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOPIC_NAME_TAKEN"));
        call("PATCH","/api/topics/"+id,"{\"name\":\"타인 변경\"}",otherBearer).andExpect(status().isNotFound());
        assertThat(topics.findById(id).orElseThrow().getName()).isEqualTo("새 이름");
        assertThatThrownBy(() -> topics.saveAndFlush(new Topic(userId,"db",9,Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(second).isNotNull();
    }
    @Test
    void normalizesCodePointsAndPreventsConcurrentDuplicateCreation() throws Exception {
        topic("😀".repeat(50));
        for(String name:new String[]{"😀".repeat(51)," ","a/b"}) {
            call("POST","/api/topics",mapper.writeValueAsString(java.util.Map.of("name",name)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        CyclicBarrier gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(() -> { gate.await(); return call("POST","/api/topics","{\"name\":\"경합\"}").andReturn().getResponse().getStatus(); });
            var b=executor.submit(() -> { gate.await(); return call("POST","/api/topics","{\"name\":\"경합\"}").andReturn().getResponse().getStatus(); });
            assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(topics.list(userId)).hasSize(2);
    }
}
