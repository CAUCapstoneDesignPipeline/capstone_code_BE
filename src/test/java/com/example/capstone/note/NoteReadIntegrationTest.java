package com.example.capstone.note;

import java.time.Instant;
import java.util.List;
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
import com.example.capstone.support.ApiIntegrationSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NoteReadIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Autowired private NoteRepository notes;
    @Test
    void createsDetailsAndListsDefaultsFiltersSortsAndOwnedCounts() throws Exception {
        call("GET","/api/notes",null).andExpect(jsonPath("$.notes").isEmpty());
        var initial=json(call("POST","/api/notes","{\"title\":\"  e\u0301  \"}").andExpect(status().isCreated()));
        String id=initial.path("id").asString();
        assertThat(initial.has("topicId")).isTrue(); assertThat(initial.path("topicId").isNull()).isTrue();
        assertThat(initial.path("title").asString()).isEqualTo("é"); assertThat(initial.path("body").asString()).isEmpty();
        assertThat(initial.path("version").asInt()).isZero();
        UUID topic=topic("주제");
        String body=" \ne\u0301"+"😀".repeat(100);
        UUID second=note("a",topic,body);
        call("GET","/api/notes/"+second,null).andExpect(jsonPath("$.body").value(" \né"+"😀".repeat(100)));
        var list=json(call("GET","/api/notes",null));
        assertThat(list.path("notes").get(0).path("id").asString()).isEqualTo(second.toString());
        assertThat(list.path("notes").get(0).has("body")).isFalse();
        String snippet=list.path("notes").get(0).path("snippet").asString();
        assertThat(snippet.codePointCount(0,snippet.length())).isEqualTo(80);
        call("GET","/api/notes?topicId=none",null).andExpect(jsonPath("$.notes.length()").value(1));
        call("GET","/api/notes?topicId="+topic,null).andExpect(jsonPath("$.notes[0].id").value(second.toString()));
        jdbc.update("update note set updated_at='2020-01-01T00:00:00Z' where id=?",second);
        call("GET","/api/notes?sort=updated",null).andExpect(jsonPath("$.notes[0].id").value(id));
        call("GET","/api/topics",null).andExpect(jsonPath("$.topics[0].noteCount").value(1)).andExpect(jsonPath("$.unassignedNoteCount").value(1));
        call("GET","/api/notes/"+id,null,otherBearer).andExpect(status().isNotFound());
        call("GET","/api/notes?topicId="+topic,null,otherBearer).andExpect(status().isNotFound());
        call("POST","/api/notes","{\"title\":\"other\",\"topicId\":\""+topic+"\"}",otherBearer).andExpect(status().isNotFound());
        call("GET","/api/notes?topicId="+UUID.randomUUID(),null).andExpect(status().isNotFound());
        call("GET","/api/notes?topicId=bad",null).andExpect(status().isBadRequest());
        call("GET","/api/notes?sort=bad",null).andExpect(status().isBadRequest());
    }
    @Test
    void enforcesScopedTitlesAndRealUnassignedConstraintIncludingConcurrentRequests() throws Exception {
        UUID a=topic("a"),b=topic("b");
        note("same",a,""); note("same",b,""); note("same",null,""); note("SAME",null,"");
        call("POST","/api/notes","{\"title\":\"same\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOTE_TITLE_TAKEN")).andExpect(jsonPath("$.error.details.titles[0]").value("same"));
        assertThatThrownBy(() -> notes.saveAndFlush(new Note(userId,null,"same","",Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
        CyclicBarrier gate=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> { gate.await(); return call("POST","/api/notes","{\"title\":\"race\"}").andReturn().getResponse().getStatus(); });
            var second=executor.submit(() -> { gate.await(); return call("POST","/api/notes","{\"title\":\"race\"}").andReturn().getResponse().getStatus(); });
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
    }
    @Test
    void checksNoteSpecificBodyAndTitleLimitsWithoutTrimmingBody() throws Exception {
        note("😀".repeat(200),null,"😀".repeat(1000000));
        call("POST","/api/notes",mapper.writeValueAsString(java.util.Map.of("title","too long","body","😀".repeat(1000001))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details.fields[0].field").value("body"));
        call("POST","/api/notes",mapper.writeValueAsString(java.util.Map.of("title","😀".repeat(201))))
                .andExpect(status().isBadRequest());
        call("POST","/api/notes","{\"title\":\"explicit null\",\"body\":null}").andExpect(status().isBadRequest());
    }
}
