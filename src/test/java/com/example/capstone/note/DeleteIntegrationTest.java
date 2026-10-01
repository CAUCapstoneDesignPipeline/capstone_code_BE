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
class DeleteIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test
    void deletesOnlyOwnedEvidenceFreeNotesAndUnassignsTopicsWithoutChangingVersion() throws Exception {
        UUID topic=topic("topic"),plain=note("plain",topic,"body"),guarded=note("guarded",topic,"quote");
        call("DELETE","/api/notes/"+plain,null,otherBearer).andExpect(status().isNotFound());
        call("DELETE","/api/notes/"+plain,null).andExpect(status().isNoContent());
        call("GET","/api/notes/"+plain,null).andExpect(status().isNotFound());
        jdbc.update("insert into evidence_span (user_id,note_id,note_version,start_offset,end_offset,quote) values (?,?,0,0,5,'quote')",userId,guarded);
        String before=call("GET","/api/notes/"+guarded,null).andReturn().getResponse().getContentAsString();
        call("DELETE","/api/notes/"+guarded,null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_DELETE_BLOCKED"))
                .andExpect(jsonPath("$.error.message").value("근거가 연결된 노트는 아직 삭제할 수 없습니다."))
                .andExpect(jsonPath("$.error.details").doesNotExist());
        assertThat(call("GET","/api/notes/"+guarded,null).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select status from evidence_span where note_id=?",String.class,guarded)).isEqualTo("valid");
        jdbc.update("update note set version=4,updated_at='2020-01-01T00:00:00Z' where id=?",guarded);
        call("DELETE","/api/topics/"+topic,null,otherBearer).andExpect(status().isNotFound());
        call("DELETE","/api/topics/"+topic,null).andExpect(status().isNoContent());
        call("GET","/api/notes/"+guarded,null).andExpect(jsonPath("$.topicId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.body").value("quote")).andExpect(jsonPath("$.version").value(4));
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics").isEmpty()).andExpect(jsonPath("$.unassignedNoteCount").value(1));
        call("DELETE","/api/topics/"+topic,null).andExpect(status().isNotFound());
    }
    @Test
    void topicTitleConflictRollsBackAllMovesAndTopicDeletion() throws Exception {
        UUID topic=topic("topic"),a=note("same",topic,"body"),b=note("other",topic,"other body"); note("same",null,"");
        String before=call("GET","/api/notes?sort=updated",null).andReturn().getResponse().getContentAsString();
        call("DELETE","/api/topics/"+topic,null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_TITLE_TAKEN"))
                .andExpect(jsonPath("$.error.details.titles[0]").value("same"));
        assertThat(call("GET","/api/notes?sort=updated",null).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics[0].noteCount").value(2));
        assertThat(jdbc.queryForObject("select count(*) from note where topic_id=?",Long.class,topic)).isEqualTo(2);
        assertThat(List.of(a,b)).hasSize(2);
    }
    @Test
    void deletionSerializesWithReorderAndUnassignedTitleCreation() throws Exception {
        UUID a=topic("a"),b=topic("b");
        String order=mapper.writeValueAsString(Map.of("topicIds",List.of(b,a)));
        CyclicBarrier gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion=executor.submit(() -> { gate.await(); return call("DELETE","/api/topics/"+a,null).andReturn().getResponse().getStatus(); });
            var reorder=executor.submit(() -> { gate.await(); return call("PUT","/api/topics/order",order).andReturn().getResponse().getStatus(); });
            assertThat(deletion.get(10,TimeUnit.SECONDS)).isEqualTo(204); assertThat(reorder.get(10,TimeUnit.SECONDS)).isIn(200,409);
        }
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics.length()").value(1)).andExpect(jsonPath("$.topics[0].id").value(b.toString()));
        UUID source=topic("source"),id=note("racing title",source,"preserve body");
        CyclicBarrier titleGate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion=executor.submit(() -> { titleGate.await(); return call("DELETE","/api/topics/"+source,null).andReturn().getResponse().getStatus(); });
            var creation=executor.submit(() -> { titleGate.await(); return call("POST","/api/notes","{\"title\":\"racing title\"}").andReturn().getResponse().getStatus(); });
            int deleted=deletion.get(10,TimeUnit.SECONDS),created=creation.get(10,TimeUnit.SECONDS);
            assertThat(List.of(deleted,created)).isIn(List.of(204,409),List.of(409,201));
            call("GET","/api/notes/"+id,null).andExpect(jsonPath("$.body").value("preserve body")).andExpect(jsonPath("$.version").value(0));
            assertThat(jdbc.queryForObject("select topic_id from note where id=?",UUID.class,id)).isEqualTo(deleted==204?null:source);
        }
    }
}
