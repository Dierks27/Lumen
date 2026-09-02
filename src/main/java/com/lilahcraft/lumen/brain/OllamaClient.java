package com.lilahcraft.lumen.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin async client for Ollama's OpenAI-compatible {@code /v1/chat/completions}
 * endpoint. Every call happens off the server thread; callers are responsible for
 * hopping back onto it before touching the world.
 */
public final class OllamaClient {

    private final ExecutorService executor;
    private final HttpClient http;

    public OllamaClient(int connectTimeoutSeconds) {
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "Lumen-Ollama-" + counter.incrementAndGet());
            // Daemon: a hung request must never keep the server from shutting down.
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .executor(this.executor)
                .build();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Sends a chat completion request.
     *
     * @return the assistant's raw content, completed exceptionally on transport errors
     */
    public CompletableFuture<String> complete(LumenConfig config, List<ChatMessage> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("temperature", config.temperature);
        body.addProperty("max_tokens", config.maxTokens);
        body.addProperty("stream", false);

        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.role());
            entry.addProperty("content", message.content());
            array.add(entry);
        }
        body.add("messages", array);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.ollamaUrl))
                    .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (config.logRawResponses) {
                        Lumen.LOGGER.info("Ollama HTTP {} body: {}", response.statusCode(), response.body());
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new IllegalStateException("Ollama returned HTTP " + response.statusCode()
                                + ": " + abbreviate(response.body()));
                    }
                    return extractContent(response.body());
                });
    }

    /**
     * Digs the assistant text out of the response. Handles the OpenAI shape used by
     * {@code /v1/chat/completions} plus Ollama's own {@code /api/chat} and
     * {@code /api/generate} shapes, so a mistyped URL still produces something useful.
     */
    private static String extractContent(String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IllegalStateException("Ollama response was not a JSON object");
        }
        JsonObject object = root.getAsJsonObject();

        JsonElement error = object.get("error");
        if (error != null && !error.isJsonNull()) {
            throw new IllegalStateException("Ollama error: " + error);
        }

        // OpenAI compatible: { "choices": [ { "message": { "content": "..." } } ] }
        JsonElement choices = object.get("choices");
        if (choices != null && choices.isJsonArray() && !choices.getAsJsonArray().isEmpty()) {
            JsonElement first = choices.getAsJsonArray().get(0);
            if (first.isJsonObject()) {
                JsonElement message = first.getAsJsonObject().get("message");
                if (message != null && message.isJsonObject()) {
                    JsonElement content = message.getAsJsonObject().get("content");
                    if (content != null && content.isJsonPrimitive()) {
                        return content.getAsString();
                    }
                }
                JsonElement text = first.getAsJsonObject().get("text");
                if (text != null && text.isJsonPrimitive()) {
                    return text.getAsString();
                }
            }
        }

        // Native /api/chat: { "message": { "content": "..." } }
        JsonElement message = object.get("message");
        if (message != null && message.isJsonObject()) {
            JsonElement content = message.getAsJsonObject().get("content");
            if (content != null && content.isJsonPrimitive()) {
                return content.getAsString();
            }
        }

        // Native /api/generate: { "response": "..." }
        JsonElement response = object.get("response");
        if (response != null && response.isJsonPrimitive()) {
            return response.getAsString();
        }

        throw new IllegalStateException("No assistant content in Ollama response: " + abbreviate(body));
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
