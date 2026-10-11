package com.example.capstone.note;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.example.capstone.support.ApiIntegrationSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NoteSearchIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test
    void searchesLiteralTitlesAndBodiesWithOwnedFilterAndSortAndBlankQuery() throws Exception {
        UUID topic=topic("topic"),title=note("a Needle title",topic,"😀".repeat(90));
        UUID body=note("z body",null,"prefix NeEdLe suffix");
        note("nothing",null,"ordinary"); note("m empty NEEDLE",null,"");
        call("POST","/api/notes","{\"title\":\"foreign NEEDLE\",\"body\":\"NEEDLE\"}",otherBearer).andExpect(status().isCreated());
        var result=json(search("  needle  ",null,"title").andExpect(status().isOk()));
        assertThat(result.path("notes").size()).isEqualTo(3);
        assertThat(result.path("notes").get(0).path("id").asString()).isEqualTo(title.toString());
        assertThat(result.path("notes").get(0).path("snippet").asString()).isEqualTo("😀".repeat(80));
        assertThat(result.path("notes").get(0).has("body")).isFalse();
        search("NEEDLE",topic.toString(),"title").andExpect(jsonPath("$.notes.length()").value(1));
        search("NEEDLE","none","title").andExpect(jsonPath("$.notes.length()").value(2));
        jdbc.update("update note set updated_at='2020-01-01T00:00:00Z' where id=?",title);
        var updated=json(search("needle",null,"updated"));
        assertThat(updated.path("notes").get(2).path("id").asString()).isEqualTo(title.toString());
        var baseline=json(call("GET","/api/notes",null));
        assertThat(json(search("  ",null,"title"))).isEqualTo(baseline);
        search("missing",null,"title").andExpect(jsonPath("$.notes").isEmpty());
        note("symbols",null,"100% _ \\ literal");
        for(String q:List.of("%","_","\\")) {
            search(q,null,"title").andExpect(jsonPath("$.notes.length()").value(1)).andExpect(jsonPath("$.notes[0].title").value("symbols"));
        }
        search("😀".repeat(100),null,"title").andExpect(status().isOk());
        search("😀".repeat(101),null,"title").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields[0].reason").value("too_long"));
        search("needle",topic.toString(),"title",otherBearer).andExpect(status().isNotFound());
        search("needle",UUID.randomUUID().toString(),"title").andExpect(status().isNotFound());
        assertThat(body).isNotNull();
    }
    @Test
    void snippetsUseFirstBodyMatchCodePointsAndShiftNearEnd() throws Exception {
        UUID window=note("window",null,"😀".repeat(50)+"Needle"+"가".repeat(100));
        UUID end=note("end",null,"😀".repeat(100)+"needle");
        note("empty needle",null,"");
        var result=json(search("needle",null,"title"));
        assertThat(result.path("notes").size()).isEqualTo(3);
        for(var item:result.path("notes")) {
            String snippet=item.path("snippet").asString();
            if(item.path("id").asString().equals(window.toString())) {
                assertThat(snippet).isEqualTo("😀".repeat(20)+"Needle"+"가".repeat(54));
            } else if(item.path("id").asString().equals(end.toString())) {
                assertThat(snippet).isEqualTo("😀".repeat(74)+"needle");
            } else { assertThat(snippet).isEmpty(); }
            assertThat(snippet.codePointCount(0,snippet.length())).isLessThanOrEqualTo(80);
            assertThat(snippet).doesNotContain("…"); assertThat(item.has("body")).isFalse();
        }
    }
    private ResultActions search(String q,String topic,String sort) throws Exception { return search(q,topic,sort,bearer); }
    private ResultActions search(String q,String topic,String sort,String token) throws Exception {
        var request=get("/api/notes").header("Authorization","Bearer "+token).param("q",q).param("sort",sort);
        if(topic!=null) request.param("topicId",topic);
        return mvc.perform(request);
    }
}
