package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import searchengine.config.assistant.AssistantConfig;
import searchengine.dto.assistant.ChatMessage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesResponsesApiOutputText() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"status":"completed","output":[{"type":"message","content":[
                    {"type":"output_text","text":"Ответ [1]"}]}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey("test-key");
        config.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        config.getLlm().setModel("gpt-test");
        config.getLlm().setRetryAttempts(1);
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        String result = client.complete(List.of(
                new ChatMessage("system", "Отвечай по контексту"),
                new ChatMessage("user", "Вопрос")));

        assertThat(result).isEqualTo("Ответ [1]");
        assertThat(client.isConfigured()).isTrue();
        assertThat(new ObjectMapper().readTree(requestBody.get())
                .path("text").path("format").path("type").asText()).isEqualTo("text");
    }

    @Test
    void localStructuredRequestIncludesSchemaInInstructions() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"status":"completed","output":[{"type":"message","content":[
                    {"type":"output_text","text":"{\\"value\\":\\"ok\\"}"}]}]}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey("local-test-key");
        config.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.getLlm().setModel("local-test-model");
        config.getLlm().setRetryAttempts(1);
        LlmClient client = new LlmClient(config, mapper, WebClient.builder());

        String result = client.completeJson(
                List.of(new ChatMessage("system", "Classify"), new ChatMessage("user", "Input")),
                "result",
                mapper.readTree("""
                        {"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}
                        """));

        JsonNode request = mapper.readTree(requestBody.get());
        assertThat(result).isEqualTo("{\"value\":\"ok\"}");
        assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("text");
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(768);
        assertThat(request.path("instructions").asText()).contains("JSON Schema", "required", "value");
    }

    @Test
    void blankApiKeyDisablesExternalCalls() {
        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey(" ");
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    void dockerHostLmStudioEndpointIsRecognizedAsLocal() {
        AssistantConfig config = new AssistantConfig();
        config.getLlm().setBaseUrl("http://host.docker.internal:1234/v1");
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        assertThat(client.isLocalProvider()).isTrue();
    }

    @Test
    void streamsResponsesApiTextDeltas() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"Первая "}

                    event: response.output_text.delta
                    data: {"type":"response.output_text.delta","delta":"часть"}

                    data: [DONE]

                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey("test-key");
        config.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        config.getLlm().setModel("gpt-test");
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        List<String> deltas = client.stream(List.of(new ChatMessage("user", "Вопрос")))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(deltas).containsExactly("Первая ", "часть");
        JsonNode request = new ObjectMapper().readTree(requestBody.get());
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(256);
        assertThat(request.path("input").get(0).path("content").asText()).startsWith("/no_think\n");
    }

    @Test
    void cachesIdenticalCompletedRequestsWithoutSecondProviderCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"status\":\"completed\",\"output_text\":\"Кэшированный ответ\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AssistantConfig config = configured();
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());
        List<ChatMessage> prompt = List.of(new ChatMessage("user", "Один и тот же вопрос"));

        assertThat(client.complete(prompt)).isEqualTo("Кэшированный ответ");
        assertThat(client.complete(prompt)).isEqualTo("Кэшированный ответ");

        assertThat(calls).hasValue(1);
        assertThat(client.runtimeStatus()).containsEntry("cacheHits", 1L)
                .containsEntry("cacheMisses", 1L);
    }

    @Test
    void opensCircuitAfterConsecutiveProviderFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        AssistantConfig config = configured();
        config.getLlm().setCircuitFailureThreshold(2);
        config.getLlm().setCircuitCooldownSeconds(60);
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        assertThatThrownBy(() -> client.complete(List.of(new ChatMessage("user", "Первый"))))
                .isInstanceOf(LlmClient.LlmException.class);
        assertThatThrownBy(() -> client.complete(List.of(new ChatMessage("user", "Второй"))))
                .isInstanceOf(LlmClient.LlmException.class);
        assertThatThrownBy(() -> client.complete(List.of(new ChatMessage("user", "Третий"))))
                .isInstanceOf(LlmClient.LlmException.class)
                .hasMessageContaining("восстанавливается");

        assertThat(calls).hasValue(2);
        assertThat(client.runtimeStatus()).containsEntry("circuit", "open")
                .containsEntry("consecutiveFailures", 2);
    }

    private AssistantConfig configured() {
        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey("test-key");
        config.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        config.getLlm().setModel("gpt-test");
        config.getLlm().setRetryAttempts(1);
        return config;
    }
}
