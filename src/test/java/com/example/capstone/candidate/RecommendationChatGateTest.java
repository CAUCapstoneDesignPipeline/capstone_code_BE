package com.example.capstone.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import com.example.capstone.analysis.client.AiHttpClient;
import com.example.capstone.analysis.client.AiResponseValidator;
import com.example.capstone.analysis.client.AiSchemaValidator;
import com.example.capstone.analysis.config.AiClientProperties;
import com.example.capstone.analysis.config.AiFeatureGate;
import com.example.capstone.analysis.config.AiPipelineConfiguration;
import com.example.capstone.analysis.config.AiPipelineProperties;
import com.example.capstone.analysis.config.AiProperties;
import com.example.capstone.analysis.config.AiRuntimeProperties;
import com.example.capstone.analysis.repository.RecommendationRepository;
import com.example.capstone.analysis.support.ScriptedAiServer;
import com.example.capstone.candidate.dto.request.RecommendationChatRequest;
import com.example.capstone.candidate.service.RecommendationChatService;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
import static org.junit.jupiter.api.Assertions.*;

/** 챗봇도 분석과 같은 끄기 수단(suspension 파일·응답 불일치 격리)을 따르고, 막히면 AI를 부르지 않는다. */
class RecommendationChatGateTest {
    private static final String TOKEN="synthetic-service-token-only";
    @TempDir Path temporary;
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final AiSchemaValidator schemas=new AiSchemaValidator(mapper);
    private final RecommendationChatRequest ask=new RecommendationChatRequest(UUID.randomUUID(),List.of(new RecommendationChatRequest.Message("user","무슨 뜻이야?")));

    private RecommendationChatService service(AiHttpClient client,AiProperties flags,AiPipelineConfiguration configuration) {
        var repository=Mockito.mock(RecommendationRepository.class);
        Mockito.when(repository.chatCard(Mockito.any(),Mockito.any())).thenReturn(mapper.readTree("{\"conclusion\":\"합성 결론\",\"keywords\":[\"A\",\"B\"],\"evidence\":[]}"));
        var env=new MockEnvironment();env.setActiveProfiles("test");
        var gate=new AiFeatureGate(flags,new AiRuntimeProperties(false,temporary.resolve("suspend").toString()),new AiPipelineProperties(null,null,null,false,Map.of()),env);
        return new RecommendationChatService(repository,client,mapper,gate,configuration);
    }
    private AiHttpClient client(ScriptedAiServer server) {
        var env=new MockEnvironment();env.setActiveProfiles("test");
        return new AiHttpClient(new AiClientProperties(server.baseUri().toString(),TOKEN,null,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(2),1,1,Duration.ZERO),
                mapper,schemas,new AiResponseValidator(schemas),env);
    }
    private static String reason(ApiException failure) { assertEquals(ErrorCode.AI_UNAVAILABLE,failure.errorCode());return String.valueOf(failure.details().get("reason")); }

    @Test void suspensionAndQuarantineBlockChatBeforeAnyAiRequest() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT)) {
            var configuration=Mockito.mock(AiPipelineConfiguration.class);
            var chat=service(client(server),new AiProperties(true),configuration);
            Files.writeString(temporary.resolve("suspend"),"on");
            assertEquals("MISCONFIGURED",reason(assertThrows(ApiException.class,()->chat.chat(UUID.randomUUID(),UUID.randomUUID(),ask))));
            Files.delete(temporary.resolve("suspend"));Mockito.when(configuration.quarantined()).thenReturn(true);
            assertEquals("MISCONFIGURED",reason(assertThrows(ApiException.class,()->chat.chat(UUID.randomUUID(),UUID.randomUUID(),ask))));
            assertThrows(IllegalStateException.class,()->server.takeRequest(Duration.ofMillis(100)));
            // gate가 열리면 기존처럼 AI에 묻는다(404dabf에는 /chat이 없어 404 → UNAVAILABLE).
            Mockito.when(configuration.quarantined()).thenReturn(false);server.enqueue(new ScriptedAiServer.Reply(404,"{}",Map.of()));
            assertEquals("UNAVAILABLE",reason(assertThrows(ApiException.class,()->chat.chat(UUID.randomUUID(),UUID.randomUUID(),ask))));
            server.takeRequest(Duration.ofSeconds(1));
        }
    }
    @Test void aiOffKeepsNotDeployedReason() throws Exception {
        try(var server=new ScriptedAiServer(TOKEN,AiHttpClient.REQUEST_LIMIT)) {
            var env=new MockEnvironment();env.setActiveProfiles("test");
            var off=new AiHttpClient(new AiProperties(false),new AiClientProperties(server.baseUri().toString(),TOKEN,null,Duration.ofSeconds(1),Duration.ofSeconds(2),Duration.ofSeconds(2),1,1,Duration.ZERO),
                    mapper,schemas,new AiResponseValidator(schemas),env);
            var chat=service(off,new AiProperties(false),Mockito.mock(AiPipelineConfiguration.class));
            assertEquals("NOT_DEPLOYED",reason(assertThrows(ApiException.class,()->chat.chat(UUID.randomUUID(),UUID.randomUUID(),ask))));
        }
    }
}
