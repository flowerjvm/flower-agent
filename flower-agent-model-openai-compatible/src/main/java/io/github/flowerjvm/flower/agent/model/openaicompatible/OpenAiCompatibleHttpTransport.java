package io.github.flowerjvm.flower.agent.model.openaicompatible;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface OpenAiCompatibleHttpTransport {

    CompletableFuture<HttpResponse<String>> send(HttpRequest request);
}
