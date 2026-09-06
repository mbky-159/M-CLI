package com.paicli.eval.benchmark;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingLlmClientTest {

    @Test
    void recordsOnlySafeMetadataAndAggregatesUsage(@TempDir Path tempDir) throws Exception {
        Path trace = tempDir.resolve("llm-calls.jsonl");
        TracingLlmClient client = new TracingLlmClient(new StubClient(), trace);

        LlmClient.ChatResponse response = client.chat(
                List.of(LlmClient.Message.user("Bearer should-not-be-recorded")),
                List.of(new LlmClient.Tool("read_file", "secret description", null)));

        assertEquals("done", response.content());
        assertEquals(new TracingLlmClient.Metrics(1, 11, 7, 3, 1, client.metrics().elapsedMillis()),
                client.metrics());
        String persisted = Files.readString(trace);
        assertTrue(persisted.contains("\"provider\":\"stub\""));
        assertTrue(persisted.contains("\"returnedTools\":[\"write_file\"]"));
        assertFalse(persisted.contains("should-not-be-recorded"));
        assertFalse(persisted.contains("secret description"));
        assertFalse(persisted.contains("{\\\"path\\\""));
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return response();
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            listener.onContentDelta("done");
            return response();
        }

        private ChatResponse response() {
            return new ChatResponse(
                    "assistant",
                    "done",
                    "hidden reasoning",
                    List.of(new ToolCall("call-1", new ToolCall.Function("write_file", "{\"path\":\"secret\"}"))),
                    11,
                    7,
                    3);
        }

        @Override
        public String getModelName() {
            return "trace-test";
        }

        @Override
        public String getProviderName() {
            return "stub";
        }
    }
}
