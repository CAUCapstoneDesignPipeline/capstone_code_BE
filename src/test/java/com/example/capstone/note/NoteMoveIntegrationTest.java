package com.example.capstone.note;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NoteMoveIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test
    void movesWithoutChangingContentOrVersionAndRejectsDuplicateAndForeignDestinations() throws Exception {
        UUID source=topic("source"),target=topic("target"),id=note("same",source,"본문");
        note("same",target,""); note("same",null,"");
        String path="/api/notes/"+id,move=path+"/topic";
        jdbc.update("update note set version=7,updated_at='2020-01-01T00:00:00Z' where id=?",id);
        var before=json(call("GET",path,null));
        call("PUT",move,target(target)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("옮길 주제에 같은 제목의 노트가 있어 옮기지 못했습니다."));
        call("PUT",move,"{\"topicId\":null}").andExpect(status().isConflict());
        call("PUT",move,"{}").andExpect(status().isBadRequest());
        call("PUT",move,target(target),otherBearer).andExpect(status().isNotFound());
        UUID foreign=UUID.fromString(json(call("POST","/api/topics","{\"name\":\"foreign\"}",otherBearer)).path("id").asString());
        call("PUT",move,target(foreign)).andExpect(status().isNotFound());
        assertThat(json(call("GET",path,null))).isEqualTo(before);
        UUID allowed=topic("allowed");
        var moved=json(call("PUT",move,target(allowed)).andExpect(status().isOk()));
        assertThat(moved.path("version").asInt()).isEqualTo(7);
        assertThat(moved.path("title")).isEqualTo(before.path("title"));
        assertThat(moved.path("body")).isEqualTo(before.path("body"));
        assertThat(moved.path("createdAt")).isEqualTo(before.path("createdAt"));
        assertThat(moved.path("updatedAt")).isNotEqualTo(before.path("updatedAt"));
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics[0].noteCount").value(0)).andExpect(jsonPath("$.topics[2].noteCount").value(1));
        // A different title can move into unassigned, preserving the version from editing.
        call("PUT",path,mapper.writeValueAsString(Map.of("title","unique","body","본문","version",7))).andExpect(status().isOk());
        call("PUT",move,"{\"topicId\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.topicId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").value(8));
    }
    @Test
    void concurrentBodySaveAndMovePreserveBothResults() throws Exception {
        UUID source=topic("source"),destination=topic("destination"),id=note("title",source,"old body");
        String path="/api/notes/"+id;
        CyclicBarrier gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var save=executor.submit(() -> { gate.await(); return call("PUT",path,"{\"title\":\"edited\",\"body\":\"new body\",\"version\":0}").andReturn().getResponse().getStatus(); });
            var move=executor.submit(() -> { gate.await(); return call("PUT",path+"/topic",target(destination)).andReturn().getResponse().getStatus(); });
            assertThat(save.get(10,TimeUnit.SECONDS)).isEqualTo(200); assertThat(move.get(10,TimeUnit.SECONDS)).isEqualTo(200);
        }
        call("GET",path,null).andExpect(jsonPath("$.topicId").value(destination.toString()))
                .andExpect(jsonPath("$.title").value("edited")).andExpect(jsonPath("$.body").value("new body"))
                .andExpect(jsonPath("$.version").value(1));
    }
    private String target(UUID id) { return mapper.writeValueAsString(Map.of("topicId",id)); }
}
