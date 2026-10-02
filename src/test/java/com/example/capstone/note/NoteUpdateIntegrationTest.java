package com.example.capstone.note;

import java.util.List;
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
class NoteUpdateIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test
    void savesWithVersionAndRejectsStaleDuplicatesAndForeignNotesWithoutMutation() throws Exception {
        UUID topic=topic("topic"),id=note("original",topic,"body"); note("taken",topic,"");
        String path="/api/notes/"+id;
        call("PUT",path,body("updated"," e\u0301 ",0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.body").value(" é "))
                .andExpect(jsonPath("$.topicId").value(topic.toString()));
        String snapshot=call("GET",path,null).andReturn().getResponse().getContentAsString();
        call("PUT",path,body("stale","lost",0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_CONFLICT"))
                .andExpect(jsonPath("$.error.details.current.body").value(" é "))
                .andExpect(jsonPath("$.error.details.current.version").value(1));
        call("PUT",path,body("taken","lost",1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_TITLE_TAKEN"));
        call("PUT",path,body("foreign","lost",1),otherBearer).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("다른 곳에서 삭제된 노트입니다"));
        call("PUT",path,"{\"title\":\"missing body/version\"}").andExpect(status().isBadRequest());
        assertThat(call("GET",path,null).andReturn().getResponse().getContentAsString()).isEqualTo(snapshot);
        call("PUT",path,body("updated"," é ",1)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }
    @Test
    void simultaneousSameVersionWritesKeepOnlyWinnerAndCurrentResponse() throws Exception {
        UUID id=note("start",null,"body"); String path="/api/notes/"+id;
        CyclicBarrier gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(() -> { gate.await(); return call("PUT",path,body("first","first body",0)).andReturn().getResponse(); });
            var b=executor.submit(() -> { gate.await(); return call("PUT",path,body("second","second body",0)).andReturn().getResponse(); });
            var first=a.get(10,TimeUnit.SECONDS); var second=b.get(10,TimeUnit.SECONDS);
            assertThat(List.of(first.getStatus(),second.getStatus())).containsExactlyInAnyOrder(200,409);
            var winner=mapper.readTree((first.getStatus()==200?first:second).getContentAsString());
            var loser=mapper.readTree((first.getStatus()==409?first:second).getContentAsString());
            assertThat(loser.path("error").path("code").asString()).isEqualTo("NOTE_CONFLICT");
            var current=json(call("GET",path,null));
            assertThat(current.path("title").asString()).isEqualTo(winner.path("title").asString());
            assertThat(current.path("body").asString()).isEqualTo(winner.path("body").asString());
            assertThat(current.path("version").asInt()).isEqualTo(1);
            assertThat(loser.path("error").path("details").path("current")).isEqualTo(current);
        }
    }
    private String body(String title,String body,int version) { return mapper.writeValueAsString(Map.of("title",title,"body",body,"version",version)); }
}
