package io.github.flowerjvm.flower.agent.model.openaicompatible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelGateway;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentModelRequest;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.agent.model.AgentRole;
import io.github.flowerjvm.flower.agent.model.AgentUsage;
import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolDefinition;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Agent model gateway for OpenAI-compatible {@code /chat/completions}
 * endpoints.
 */
public final class OpenAiCompatibleAgentModelGateway implements AgentModelGateway {

    private static final Set<String> RESERVED_EXTRA_BODY_FIELDS = Set.of(
            "model", "messages", "tools", "stream");
    private static final Pattern OPENAI_KEY_PATTERN = Pattern.compile(
            "sk-[A-Za-z0-9_*\\-]{8,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVIDER_TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final TypeReference<Map<String, Object>> ARGUMENT_MAP = new TypeReference<>() {
    };

    private final OpenAiCompatibleAgentGatewayConfig config;
    private final OpenAiCompatibleHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenAiCompatibleAgentModelGateway(OpenAiCompatibleAgentGatewayConfig config) {
        this(config, config.createHttpClient(), new ObjectMapper(), Clock.systemUTC());
    }

    public OpenAiCompatibleAgentModelGateway(
            OpenAiCompatibleAgentGatewayConfig config,
            HttpClient httpClient,
            ObjectMapper objectMapper
    ) {
        this(config, httpClient, objectMapper, Clock.systemUTC());
    }

    public OpenAiCompatibleAgentModelGateway(
            OpenAiCompatibleAgentGatewayConfig config,
            HttpClient httpClient,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this(
                config,
                request -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()),
                objectMapper,
                clock);
        Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    OpenAiCompatibleAgentModelGateway(
            OpenAiCompatibleAgentGatewayConfig config,
            OpenAiCompatibleHttpTransport transport,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public AgentModelCall submit(AgentModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String callId = "openai-compatible-agent-call-" + UUID.randomUUID();
        CompletableFuture<HttpResponse<String>> transportFuture;
        CompletableFuture<AgentModelResponse> resultFuture;
        try {
            transportFuture = transport.send(toHttpRequest(request));
            if (transportFuture == null) {
                throw new IllegalStateException("OpenAI-compatible transport returned null future");
            }
            resultFuture = transportFuture.handle((response, failure) -> {
                if (failure != null) {
                    Throwable actual = unwrap(failure);
                    if (actual instanceof CancellationException cancellation) {
                        throw cancellation;
                    }
                    throw new CompletionException(OpenAiCompatibleAgentGatewayException.transport(actual));
                }
                return toAgentModelResponse(request, response);
            });
        } catch (Throwable failure) {
            Throwable actual = failure instanceof OpenAiCompatibleAgentGatewayException
                    ? failure
                    : OpenAiCompatibleAgentGatewayException.request(
                            "Failed to create OpenAI-compatible agent request",
                            failure);
            transportFuture = CompletableFuture.failedFuture(actual);
            resultFuture = CompletableFuture.failedFuture(actual);
        }
        return new OpenAiCompatibleAgentModelCall(callId, transportFuture, resultFuture);
    }

    private HttpRequest toHttpRequest(AgentModelRequest request) {
        validateTranscript(request.messages());
        try {
            ToolNameMapping toolNames = toolNameMapping(request);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(config.chatCompletionsUri())
                    .timeout(request.timeout())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(toBody(request, toolNames)),
                            StandardCharsets.UTF_8));
            config.headers().forEach(builder::setHeader);
            builder.setHeader("Accept", "application/json");
            builder.setHeader("Content-Type", "application/json");
            config.apiKey().ifPresent(apiKey -> builder.setHeader("Authorization", "Bearer " + apiKey));
            return builder.build();
        } catch (OpenAiCompatibleAgentGatewayException failure) {
            throw failure;
        } catch (Exception failure) {
            throw OpenAiCompatibleAgentGatewayException.request(
                    "Failed to encode OpenAI-compatible agent request",
                    failure);
        }
    }

    private ObjectNode toBody(AgentModelRequest request, ToolNameMapping toolNames) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.modelId());
        body.set("messages", toMessages(request.messages(), toolNames));
        if (!request.tools().isEmpty()) {
            body.set("tools", toTools(request.tools(), toolNames));
        }
        body.put("stream", false);
        putOption(body, request, OpenAiCompatibleAgentOptions.TEMPERATURE, "temperature");
        putOption(body, request, OpenAiCompatibleAgentOptions.MAX_TOKENS, "max_tokens");
        putOption(body, request, OpenAiCompatibleAgentOptions.MAX_COMPLETION_TOKENS, "max_completion_tokens");
        putOption(body, request, OpenAiCompatibleAgentOptions.TOP_P, "top_p");
        putOption(body, request, OpenAiCompatibleAgentOptions.FREQUENCY_PENALTY, "frequency_penalty");
        putOption(body, request, OpenAiCompatibleAgentOptions.PRESENCE_PENALTY, "presence_penalty");
        putOption(body, request, OpenAiCompatibleAgentOptions.STOP_SEQUENCES, "stop");
        putToolChoice(body, request, toolNames);
        putOption(body, request, OpenAiCompatibleAgentOptions.PARALLEL_TOOL_CALLS, "parallel_tool_calls");
        putExtraBody(body, request);
        return body;
    }

    private ArrayNode toMessages(List<AgentMessage> messages, ToolNameMapping toolNames) {
        ArrayNode output = objectMapper.createArrayNode();
        for (AgentMessage message : messages) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("role", role(message.role()));
            switch (message.role()) {
                case SYSTEM, USER -> item.put("content", message.content());
                case ASSISTANT -> writeAssistantMessage(item, message, toolNames);
                case TOOL -> {
                    item.put("tool_call_id", message.toolCallId());
                    item.put("content", message.content());
                }
            }
            output.add(item);
        }
        return output;
    }

    private void writeAssistantMessage(
            ObjectNode item,
            AgentMessage message,
            ToolNameMapping toolNames
    ) {
        if (message.content().isBlank() && !message.toolCalls().isEmpty()) {
            item.putNull("content");
        } else {
            item.put("content", message.content());
        }
        if (message.toolCalls().isEmpty()) {
            return;
        }
        ArrayNode toolCalls = objectMapper.createArrayNode();
        for (ToolCall call : message.toolCalls()) {
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", toolNames.providerName(call.toolName()));
            try {
                function.put("arguments", objectMapper.writeValueAsString(call.arguments()));
            } catch (JsonProcessingException failure) {
                throw OpenAiCompatibleAgentGatewayException.request(
                        "Failed to encode arguments for tool call " + call.callId(),
                        failure);
            }
            ObjectNode itemCall = objectMapper.createObjectNode();
            itemCall.put("id", call.callId());
            itemCall.put("type", "function");
            itemCall.set("function", function);
            toolCalls.add(itemCall);
        }
        item.set("tool_calls", toolCalls);
    }

    private ArrayNode toTools(List<ToolDefinition> definitions, ToolNameMapping toolNames) {
        ArrayNode tools = objectMapper.createArrayNode();
        for (ToolDefinition definition : definitions) {
            ObjectNode function = objectMapper.createObjectNode();
            function.put("name", toolNames.providerName(definition.name()));
            if (!definition.description().isBlank()) {
                function.put("description", definition.description());
            }
            function.set("parameters", objectMapper.valueToTree(definition.inputSchema()));
            ObjectNode tool = objectMapper.createObjectNode();
            tool.put("type", "function");
            tool.set("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private void putOption(ObjectNode body, AgentModelRequest request, String optionKey, String jsonKey) {
        Object value = request.metadata().get(optionKey);
        if (value != null) {
            body.set(jsonKey, objectMapper.valueToTree(value));
        }
    }

    private void putToolChoice(
            ObjectNode body,
            AgentModelRequest request,
            ToolNameMapping toolNames
    ) {
        Object value = request.metadata().get(OpenAiCompatibleAgentOptions.TOOL_CHOICE);
        if (value == null) {
            return;
        }
        JsonNode choice = objectMapper.valueToTree(value);
        if (choice.isObject()) {
            JsonNode name = choice.path("function").path("name");
            if (name.isTextual()) {
                String internalName = name.asText();
                if (!toolNames.containsInternalName(internalName)) {
                    throw OpenAiCompatibleAgentGatewayException.request(
                            "toolChoice references an unregistered tool: " + internalName,
                            null);
                }
                ((ObjectNode) choice.path("function")).put(
                        "name",
                        toolNames.providerName(internalName));
            }
        }
        body.set("tool_choice", choice);
    }

    private void putExtraBody(ObjectNode body, AgentModelRequest request) {
        Object value = request.metadata().get(OpenAiCompatibleAgentOptions.EXTRA_BODY);
        if (value == null) {
            return;
        }
        if (!(value instanceof Map<?, ?> extraBody)) {
            throw OpenAiCompatibleAgentGatewayException.request(
                    OpenAiCompatibleAgentOptions.EXTRA_BODY + " must be a map",
                    null);
        }
        for (Map.Entry<?, ?> entry : extraBody.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (RESERVED_EXTRA_BODY_FIELDS.contains(key)) {
                throw OpenAiCompatibleAgentGatewayException.request(
                        "extraBody cannot replace reserved field: " + key,
                        null);
            }
            body.set(key, objectMapper.valueToTree(entry.getValue()));
        }
    }

    private AgentModelResponse toAgentModelResponse(
            AgentModelRequest request,
            HttpResponse<String> response
    ) {
        if (response == null) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "OpenAI-compatible transport completed without a response",
                    null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw OpenAiCompatibleAgentGatewayException.http(
                    response.statusCode(),
                    errorMessage(response));
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw OpenAiCompatibleAgentGatewayException.protocol(
                        "OpenAI-compatible response must contain choices[0]",
                        null);
            }
            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            if (!message.isObject()) {
                throw OpenAiCompatibleAgentGatewayException.protocol(
                        "OpenAI-compatible response choice must contain a message object",
                        null);
            }

            String content = responseContent(message.path("content"));
            ToolNameMapping toolNames = toolNameMapping(request);
            ParsedToolCalls parsedToolCalls = parseToolCalls(
                    request,
                    root,
                    message.path("tool_calls"),
                    toolNames);
            AgentUsage usage = parseUsage(root.path("usage"));
            Map<String, Object> metadata = responseMetadata(root, parsedToolCalls.generatedIds());
            String finishReason = textOrEmpty(choice.path("finish_reason"));
            AgentMessage assistant = AgentMessage.assistant(content, parsedToolCalls.calls(), clock.instant());
            return new AgentModelResponse(assistant, parsedToolCalls.calls(), usage, finishReason, metadata);
        } catch (OpenAiCompatibleAgentGatewayException failure) {
            throw failure;
        } catch (Exception failure) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "Failed to parse OpenAI-compatible agent response",
                    failure);
        }
    }

    private ParsedToolCalls parseToolCalls(
            AgentModelRequest request,
            JsonNode root,
            JsonNode toolCallsNode,
            ToolNameMapping toolNames
    ) throws JsonProcessingException {
        if (toolCallsNode == null || toolCallsNode.isMissingNode() || toolCallsNode.isNull()) {
            return new ParsedToolCalls(List.of(), false);
        }
        if (!toolCallsNode.isArray()) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "assistant tool_calls must be an array",
                    null);
        }
        List<ToolCall> calls = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        boolean generatedIds = false;
        for (int index = 0; index < toolCallsNode.size(); index++) {
            JsonNode item = toolCallsNode.get(index);
            String type = textOrEmpty(item.path("type"));
            if (!type.isEmpty() && !type.equals("function")) {
                throw OpenAiCompatibleAgentGatewayException.protocol(
                        "unsupported tool call type: " + type,
                        null);
            }
            JsonNode function = item.path("function");
            String providerToolName = requiredText(function.path("name"), "tool call function name");
            String toolName = toolNames.internalName(providerToolName);
            String callId = textOrEmpty(item.path("id"));
            if (callId.isEmpty()) {
                callId = generatedCallId(request, root, index);
                generatedIds = true;
            }
            if (!callIds.add(callId)) {
                throw OpenAiCompatibleAgentGatewayException.protocol(
                        "duplicate tool call id in provider response: " + callId,
                        null);
            }
            calls.add(new ToolCall(callId, toolName, parseArguments(function.path("arguments"))));
        }
        return new ParsedToolCalls(calls, generatedIds);
    }

    private Map<String, Object> parseArguments(JsonNode arguments) throws JsonProcessingException {
        JsonNode parsed;
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return Map.of();
        }
        if (arguments.isTextual()) {
            String raw = arguments.asText();
            if (raw.isBlank()) {
                return Map.of();
            }
            parsed = objectMapper.readTree(raw);
        } else {
            parsed = arguments;
        }
        if (!parsed.isObject()) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "tool call arguments must encode a JSON object",
                    null);
        }
        return objectMapper.convertValue(parsed, ARGUMENT_MAP);
    }

    private AgentUsage parseUsage(JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return AgentUsage.NONE;
        }
        return new AgentUsage(
                nonNegativeLong(usage.path("prompt_tokens"), "prompt_tokens"),
                nonNegativeLong(usage.path("completion_tokens"), "completion_tokens"));
    }

    private long nonNegativeLong(JsonNode value, String name) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return 0L;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0L) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "usage " + name + " must be a non-negative integer",
                    null);
        }
        return value.asLong();
    }

    private Map<String, Object> responseMetadata(JsonNode root, boolean generatedToolCallIds) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, "providerResponseId", root.path("id"));
        putText(metadata, "providerModel", root.path("model"));
        putText(metadata, "systemFingerprint", root.path("system_fingerprint"));
        if (generatedToolCallIds) {
            metadata.put("generatedToolCallIds", true);
        }
        return Map.copyOf(metadata);
    }

    private String errorMessage(HttpResponse<String> response) {
        String fallback = "OpenAI-compatible provider returned HTTP " + response.statusCode();
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String message = textOrEmpty(root.path("error").path("message"));
            return message.isBlank() ? fallback : fallback + ": " + sanitizeProviderMessage(message);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String sanitizeProviderMessage(String message) {
        String sanitized = config.apiKey()
                .map(apiKey -> message.replace(apiKey, "[REDACTED]"))
                .orElse(message);
        return OPENAI_KEY_PATTERN.matcher(sanitized).replaceAll("sk-[REDACTED]");
    }

    private static void validateTranscript(List<AgentMessage> messages) {
        if (messages.isEmpty()) {
            throw OpenAiCompatibleAgentGatewayException.request(
                    "OpenAI-compatible request requires at least one message",
                    null);
        }
        Map<String, String> pending = new LinkedHashMap<>();
        Set<String> declaredIds = new HashSet<>();
        for (AgentMessage message : messages) {
            if (!pending.isEmpty() && message.role() != AgentRole.TOOL) {
                throw OpenAiCompatibleAgentGatewayException.request(
                        "assistant tool_calls are missing tool results before the next message",
                        null);
            }
            if (message.role() == AgentRole.ASSISTANT) {
                for (ToolCall call : message.toolCalls()) {
                    if (!declaredIds.add(call.callId())) {
                        throw OpenAiCompatibleAgentGatewayException.request(
                                "duplicate assistant tool call id: " + call.callId(),
                                null);
                    }
                    pending.put(call.callId(), call.toolName());
                }
            } else if (message.role() == AgentRole.TOOL) {
                String expectedName = pending.remove(message.toolCallId());
                if (expectedName == null) {
                    throw OpenAiCompatibleAgentGatewayException.request(
                            "tool message has no matching assistant tool call: " + message.toolCallId(),
                            null);
                }
                if (!expectedName.equals(message.toolName())) {
                    throw OpenAiCompatibleAgentGatewayException.request(
                            "tool message name does not match declaration for " + message.toolCallId(),
                            null);
                }
            }
        }
        if (!pending.isEmpty()) {
            throw OpenAiCompatibleAgentGatewayException.request(
                    "assistant tool_calls are missing terminal tool results: " + pending.keySet(),
                    null);
        }
    }

    private static String responseContent(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        if (!content.isTextual()) {
            throw OpenAiCompatibleAgentGatewayException.protocol(
                    "assistant content must be a string or null",
                    null);
        }
        return content.asText();
    }

    private static String requiredText(JsonNode value, String name) {
        String text = textOrEmpty(value);
        if (text.isBlank()) {
            throw OpenAiCompatibleAgentGatewayException.protocol(name + " must not be blank", null);
        }
        return text;
    }

    private static String textOrEmpty(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static void putText(Map<String, Object> target, String key, JsonNode value) {
        String text = textOrEmpty(value);
        if (!text.isBlank()) {
            target.put(key, text);
        }
    }

    private static String generatedCallId(AgentModelRequest request, JsonNode root, int index) {
        String providerId = textOrEmpty(root.path("id"));
        String scope = providerId.isBlank()
                ? request.runId() + "_" + request.turnNumber() + "_" + request.attempt()
                : providerId;
        return "call_" + scope.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + index;
    }

    private static ToolNameMapping toolNameMapping(AgentModelRequest request) {
        Set<String> names = new LinkedHashSet<>();
        request.tools().forEach(definition -> names.add(definition.name()));
        request.messages().forEach(message -> {
            message.toolCalls().forEach(call -> names.add(call.toolName()));
            if (message.role() == AgentRole.TOOL) {
                names.add(message.toolName());
            }
        });

        Map<String, String> internalToProvider = new LinkedHashMap<>();
        Map<String, String> providerToInternal = new LinkedHashMap<>();
        for (String internalName : names) {
            String providerName = providerToolName(internalName);
            String previous = providerToInternal.putIfAbsent(providerName, internalName);
            if (previous != null && !previous.equals(internalName)) {
                throw OpenAiCompatibleAgentGatewayException.request(
                        "tool names map to the same provider alias: " + previous + ", " + internalName,
                        null);
            }
            internalToProvider.put(internalName, providerName);
        }
        return new ToolNameMapping(
                Map.copyOf(internalToProvider),
                Map.copyOf(providerToInternal),
                Set.copyOf(names));
    }

    private static String providerToolName(String internalName) {
        if (PROVIDER_TOOL_NAME_PATTERN.matcher(internalName).matches()) {
            return internalName;
        }
        String readable = internalName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (readable.isBlank()) {
            readable = "tool";
        }
        String hash = sha256(internalName).substring(0, 12);
        int maxReadableLength = 64 - "flower__".length() - hash.length();
        if (readable.length() > maxReadableLength) {
            readable = readable.substring(0, maxReadableLength);
        }
        return "flower_" + readable + "_" + hash;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String role(AgentRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ParsedToolCalls(List<ToolCall> calls, boolean generatedIds) {
    }

    private record ToolNameMapping(
            Map<String, String> internalToProvider,
            Map<String, String> providerToInternal,
            Set<String> internalNames
    ) {

        String providerName(String internalName) {
            return internalToProvider.getOrDefault(internalName, providerToolName(internalName));
        }

        String internalName(String providerName) {
            return providerToInternal.getOrDefault(providerName, providerName);
        }

        boolean containsInternalName(String internalName) {
            return internalNames.contains(internalName);
        }
    }
}
