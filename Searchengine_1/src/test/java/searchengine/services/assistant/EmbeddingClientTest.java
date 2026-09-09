package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import searchengine.config.assistant.AssistantConfig;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void normalizesAndCachesRepeatedQueryEmbedding() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"data\":[{\"index\":0,\"embedding\":[3,4]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        AssistantConfig config = new AssistantConfig();
        config.getEmbedding().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        config.getEmbedding().setApiKey("test-key");
        config.getEmbedding().setModel("text-embedding-nomic-embed-text-v1.5");
        EmbeddingClient client = new EmbeddingClient(config, new ObjectMapper(), WebClient.builder());

        float[] first = client.embedQuery("машинное обучение");
        first[0] = 99;
        float[] second = client.embedQuery("машинное обучение");

        assertThat(second).containsExactly(0.6f, 0.8f);
        assertThat(calls).hasValue(1);
        assertThat(client.runtimeStatus()).containsEntry("queryCacheHits", 1L)
                .containsEntry("queryCacheMisses", 1L);
    }
}
