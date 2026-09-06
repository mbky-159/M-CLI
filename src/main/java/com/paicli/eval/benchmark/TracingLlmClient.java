package com.paicli.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records benchmark-safe LLM call metadata without persisting prompt, response,
 * reasoning, tool arguments, or credentials. Full conversational evidence stays
 * in the separately protected {@code ConversationLedger}.
 */
public final class TracingLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient delegate;
    private final Path traceFile;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cachedInputTokens = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong elapsedMillis = new AtomicLong();

    public TracingLlmClient(LlmClient delegate, Path traceFile) throws IOException {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (traceFile == null) {
            throw new IllegalArgumentException("traceFile must not be null");
        }
        this.delegate = delegate;
        this.traceFile = traceFile.toAbsolutePath().normalize();
        Path parent = this.traceFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(this.traceFile)) {
            Files.createFile(this.traceFile);
        }
        try {
            Files.setPosixFilePermissions(this.traceFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms still get a regular private run artifact directory.
        }
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return trace(messages, tools, null);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        return trace(messages, tools, listener == null ? StreamListener.NO_OP : listener);
    }

    private ChatResponse trace(List<Message> messages,
                               List<Tool> tools,
                               StreamListener listener) throws IOException {
        int call = calls.incrementAndGet();
        long started = System.nanoTime();
        try {
            ChatResponse response = listener == null
                    ? delegate.chat(messages, tools)
                    : delegate.chat(messages, tools, listener);
            long elapsed = elapsedSince(started);
            inputTokens.addAndGet(Math.max(0, response.inputTokens()));
            outputTokens.addAndGet(Math.max(0, response.outputTokens()));
            cachedInputTokens.addAndGet(Math.max(0, response.cachedInputTokens()));
            int responseToolCalls = response.toolCalls() == null ? 0 : response.toolCalls().size();
            toolCalls.addAndGet(responseToolCalls);
            elapsedMillis.addAndGet(elapsed);
            Map<String, Object> event = baseEvent(call, messages, tools, elapsed);
            event.put("status", "SUCCESS");
            event.put("returnedTools", responseToolNames(response));
            event.put("inputTokens", Math.max(0, response.inputTokens()));
            event.put("outputTokens", Math.max(0, response.outputTokens()));
            event.put("cachedInputTokens", Math.max(0, response.cachedInputTokens()));
            append(event);
            return response;
        } catch (IOException e) {
            long elapsed = elapsedSince(started);
            elapsedMillis.addAndGet(elapsed);
            Map<String, Object> event = baseEvent(call, messages, tools, elapsed);
            event.put("status", "ERROR");
            event.put("errorType", e.getClass().getSimpleName());
            append(event);
            throw e;
        }
    }

    private Map<String, Object> baseEvent(int call,
                                          List<Message> messages,
                                          List<Tool> tools,
                                          long elapsed) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("call", call);
        event.put("provider", getProviderName());
        event.put("model", getModelName());
        event.put("messageCount", messages == null ? 0 : messages.size());
        event.put("exposedTools", toolNames(tools));
        event.put("elapsedMillis", elapsed);
        return event;
    }

    private synchronized void append(Map<String, Object> event) throws IOException {
        Files.writeString(
                traceFile,
                MAPPER.writeValueAsString(event) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private static List<String> toolNames(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream().map(Tool::name).sorted().toList();
    }

    private static List<String> responseToolNames(ChatResponse response) {
        if (response == null || response.toolCalls() == null || response.toolCalls().isEmpty()) {
            return List.of();
        }
        return response.toolCalls().stream()
                .map(call -> call.function().name())
                .sorted()
                .toList();
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    public Metrics metrics() {
        return new Metrics(
                calls.get(),
                inputTokens.get(),
                outputTokens.get(),
                cachedInputTokens.get(),
                toolCalls.get(),
                elapsedMillis.get());
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public int maxContextWindow() {
        return delegate.maxContextWindow();
    }

    @Override
    public boolean supportsPromptCaching() {
        return delegate.supportsPromptCaching();
    }

    @Override
    public boolean supportsTools() {
        return delegate.supportsTools();
    }

    @Override
    public boolean supportsImageInput() {
        return delegate.supportsImageInput();
    }

    @Override
    public String promptCacheMode() {
        return delegate.promptCacheMode();
    }

    public record Metrics(int calls,
                          long inputTokens,
                          long outputTokens,
                          long cachedInputTokens,
                          long toolCalls,
                          long elapsedMillis) {
    }
}
