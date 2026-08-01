package io.github.flowerjvm.flower.agent.model.openaicompatible;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Connection settings for an OpenAI-compatible chat completions endpoint.
 */
public final class OpenAiCompatibleAgentGatewayConfig {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final URI baseUrl;
    private final String apiKey;
    private final Duration connectTimeout;
    private final Map<String, String> headers;

    private OpenAiCompatibleAgentGatewayConfig(Builder builder) {
        this.baseUrl = requireHttpUri(builder.baseUrl);
        this.apiKey = blankToNull(builder.apiKey);
        this.connectTimeout = positive(builder.connectTimeout, "connectTimeout");
        this.headers = Map.copyOf(builder.headers);
    }

    public static Builder builder(URI baseUrl) {
        return new Builder(baseUrl);
    }

    public static Builder builder(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        return builder(URI.create(baseUrl.trim()));
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Optional<String> apiKey() {
        return Optional.ofNullable(apiKey);
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public URI chatCompletionsUri() {
        String normalized = baseUrl.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/chat/completions");
    }

    HttpClient createHttpClient() {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static URI requireHttpUri(URI value) {
        Objects.requireNonNull(value, "baseUrl must not be null");
        String scheme = value.getScheme();
        if (!value.isAbsolute() || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP(S) URI");
        }
        if (value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class Builder {

        private final URI baseUrl;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String apiKey;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        private Builder(URI baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        }

        public Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            connectTimeout = value;
            return this;
        }

        public Builder header(String name, String value) {
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("header name and value must not be blank");
            }
            headers.put(name.trim(), value.trim());
            return this;
        }

        public OpenAiCompatibleAgentGatewayConfig build() {
            return new OpenAiCompatibleAgentGatewayConfig(this);
        }
    }
}
