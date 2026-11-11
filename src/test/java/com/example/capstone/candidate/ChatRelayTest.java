package com.example.capstone.candidate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.example.capstone.candidate.service.ChatRelay;
import static org.junit.jupiter.api.Assertions.*;

class ChatRelayTest {
    private final ChatRelay relay=new ChatRelay(JsonMapper.builder().build());
    record Event(String name,Map<String,Object> data) { }

    @Test void relaysPublicEventsAndDropsThinkingUsageAndModel() throws IOException {
        var events=run("""
            event: start
            data: {"requestId":"r","conversationId":"c","model":"secret-model","t":0.1}

            event: thinking
            data: {"t":0.2}

            event: delta
            data: {"text":"쉽게 ","t":0.3}

            event: delta
            data: {"text":"말하면","t":0.4}

            event: done
            data: {"stopReason":"end_turn","usage":{"inputTokens":10},"t":0.5}

            event: delta
            data: {"text":"무시"}

            """);
        assertEquals(List.of("start","delta","delta","done"),events.stream().map(Event::name).toList());
        assertEquals(Map.of("conversationId","conv"),events.get(0).data());
        assertEquals("쉽게 말하면",events.get(1).data().get("text")+""+events.get(2).data().get("text"));
        assertEquals(Map.of("stopReason","end_turn"),events.get(3).data());
    }
    @Test void upstreamErrorBecomesPublicInterruptionWithoutInternalMessage() throws IOException {
        var events=run("event: delta\ndata: {\"text\":\"가\"}\n\nevent: error\ndata: {\"code\":\"MODEL_PROVIDER_ERROR\",\"message\":\"provider 500 detail\",\"retryable\":false}\n\n");
        var error=events.getLast();assertEquals("error",error.name());
        assertEquals(ChatRelay.INTERRUPTED,error.data().get("code"));assertEquals(false,error.data().get("retryable"));
        assertFalse(error.data().toString().contains("provider"));
    }
    @Test void streamEndingWithoutDoneIsReportedAsRetryableInterruption() throws IOException {
        var events=run("event: delta\ndata: {\"text\":\"가\"}\n\n");
        assertEquals("error",events.getLast().name());assertEquals(true,events.getLast().data().get("retryable"));
    }
    @Test void clientDisconnectClosesUpstreamSoModelCallStops() {
        AtomicBoolean closed=new AtomicBoolean();
        InputStream upstream=new ByteArrayInputStream("event: start\ndata: {}\n\nevent: delta\ndata: {\"text\":\"가\"}\n\n".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { closed.set(true);super.close(); }
        };
        assertThrows(IOException.class,()->relay.relay(upstream,"conv",(event,data)->{ if(event.equals("delta")) throw new IOException("client gone"); }));
        assertTrue(closed.get());
    }
    private List<Event> run(String sse) throws IOException {
        var events=new ArrayList<Event>();
        relay.relay(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)),"conv",(event,data)->events.add(new Event(event,data)));
        return events;
    }
}
