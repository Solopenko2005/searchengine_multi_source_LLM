package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import searchengine.config.assistant.AssistantConfig;
import searchengine.dto.assistant.ChatMessage;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/responses", exchange -> {
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
    }

    @Test
    void blankApiKeyDisablesExternalCalls() {
        AssistantConfig config = new AssistantConfig();
        config.getLlm().setApiKey(" ");
        LlmClient client = new LlmClient(config, new ObjectMapper(), WebClient.builder());

        assertThat(client.isConfigured()).isFalse();
    }
}
