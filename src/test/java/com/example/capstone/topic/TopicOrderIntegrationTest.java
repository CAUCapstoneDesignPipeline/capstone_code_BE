package com.example.capstone.topic;

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
class TopicOrderIntegrationTest extends ApiIntegrationSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16.15");
    @Test
    void reordersAtomicallyAndReturnsOnlyOwnedCurrentListOnSetMismatch() throws Exception {
        call("PUT","/api/topics/order","{\"topicIds\":[]}").andExpect(status().isOk()).andExpect(jsonPath("$.topics").isEmpty());
        UUID a=topic("a"),b=topic("b"),c=topic("c");
        call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("topicIds",List.of(c,a,b))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topics[0].id").value(c.toString()))
                .andExpect(jsonPath("$.topics[1].sortOrder").value(1));
        String before=call("GET","/api/topics",null).andReturn().getResponse().getContentAsString();
        UUID foreign=UUID.fromString(json(call("POST","/api/topics","{\"name\":\"other\"}",otherBearer)).path("id").asString());
        for(List<UUID> ids:List.of(List.<UUID>of(),List.of(a,b),List.of(a,b,foreign))) {
            call("PUT","/api/topics/order",mapper.writeValueAsString(Map.of("topicIds",ids)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TOPIC_ORDER_CONFLICT"))
                    .andExpect(jsonPath("$.error.details.current.topics.length()").value(3))
                    .andExpect(jsonPath("$.error.details.current.topics[0].id").value(c.toString()));
        }
        for(String body:List.of("{\"topicIds\":[\""+a+"\",\""+a+"\"]}","{\"topicIds\":[null]}","{\"topicIds\":[\"bad\"]}")) {
            call("PUT","/api/topics/order",body).andExpect(status().isBadRequest());
        }
        assertThat(call("GET","/api/topics",null).andReturn().getResponse().getContentAsString()).isEqualTo(before);
    }
    @Test
    void creationAndReorderSerializeWithoutPartialOrder() throws Exception {
        UUID a=topic("a"),b=topic("b");
        String order=mapper.writeValueAsString(Map.of("topicIds",List.of(b,a)));
        CyclicBarrier gate=new CyclicBarrier(2);
        int reordered;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var create=executor.submit(() -> { gate.await(); return call("POST","/api/topics","{\"name\":\"c\"}").andReturn().getResponse().getStatus(); });
            var sort=executor.submit(() -> { gate.await(); return call("PUT","/api/topics/order",order).andReturn().getResponse().getStatus(); });
            assertThat(create.get(10,TimeUnit.SECONDS)).isEqualTo(201); reordered=sort.get(10,TimeUnit.SECONDS);
        }
        var result=json(call("GET","/api/topics",null));
        assertThat(reordered).isIn(200,409);
        assertThat(result.path("topics").size()).isEqualTo(3);
        assertThat(result.path("topics").get(0).path("id").asString()).isEqualTo((reordered==200?b:a).toString());
        assertThat(result.path("topics").get(2).path("sortOrder").asInt()).isEqualTo(2);
    }
}
