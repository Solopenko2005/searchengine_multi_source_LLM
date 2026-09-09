package searchengine.services.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import searchengine.config.assistant.AssistantConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** OpenAI-compatible embedding client used by LM Studio without paid tokens. */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final AssistantConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final ConcurrentHashMap<String, CachedVector> queryCache = new ConcurrentHashMap<>();
    private final LongAdder queryCacheHits = new LongAdder();
    private final LongAdder queryCacheMisses = new LongAdder();

    public boolean isConfigured() {
        AssistantConfig.Embedding embedding = config.getEmbedding();
        return embedding.isEnabled() && hasText(baseUrl()) && hasText(embedding.getModel());
    }

    public String model() {
        return config.getEmbedding().getModel();
    }

    public float[] embedQuery(String text) {
        String prefixed = prefix(text, true);
        String cacheKey = model() + "\n" + prefixed;
        CachedVector cached = queryCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
            queryCacheHits.increment();
            return cached.vector().clone();
        }
        if (cached != null) queryCache.remove(cacheKey, cached);
        queryCacheMisses.increment();
        List<float[]> result = embed(List.of(prefixed));
        float[] vector = result.isEmpty() ? new float[0] : result.get(0);
        cacheQueryVector(cacheKey, vector);
        return vector.clone();
    }

    public List<float[]> embedDocuments(List<String> texts) {
        return embed(texts.stream().map(value -> prefix(value, false)).toList());
    }

    public Map<String, Object> runtimeStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", isConfigured());
        status.put("model", model());
        status.put("queryCacheEntries", queryCache.size());
        status.put("queryCacheHits", queryCacheHits.sum());
        status.put("queryCacheMisses", queryCacheMisses.sum());
        return status;
    }

    private void cacheQueryVector(String key, float[] vector) {
        int maxEntries = Math.max(0, config.getEmbedding().getQueryCacheMaxEntries());
        int ttlSeconds = Math.max(0, config.getEmbedding().getQueryCacheTtlSeconds());
        if (maxEntries == 0 || ttlSeconds == 0 || vector == null || vector.length == 0) return;
        if (queryCache.size() >= maxEntries) {
            long now = System.currentTimeMillis();
            queryCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
            if (queryCache.size() >= maxEntries) {
                queryCache.keySet().stream().findAny().ifPresent(queryCache::remove);
            }
        }
        queryCache.put(key, new CachedVector(vector.clone(),
                System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    private List<float[]> embed(List<String> input) {
        if (!isConfigured()) throw new IllegalStateException("Модель смыслового поиска не настроена");
        if (input == null || input.isEmpty()) return List.of();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", input);
        String response = webClientBuilder.baseUrl(trimSlash(baseUrl()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build().post().uri("/embeddings").bodyValue(body).retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(Math.max(5, config.getEmbedding().getTimeoutSeconds())));
        try {
            JsonNode data = objectMapper.readTree(response).path("data");
            List<IndexedVector> indexed = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode values = item.path("embedding");
                float[] vector = new float[values.size()];
                double norm = 0;
                for (int i = 0; i < values.size(); i++) {
                    vector[i] = (float) values.get(i).asDouble();
                    norm += vector[i] * vector[i];
                }
                if (norm > 0) {
                    float scale = (float) (1.0 / Math.sqrt(norm));
                    for (int i = 0; i < vector.length; i++) vector[i] *= scale;
                }
                indexed.add(new IndexedVector(item.path("index").asInt(indexed.size()), vector));
            }
            indexed.sort(java.util.Comparator.comparingInt(IndexedVector::index));
            if (indexed.size() != input.size()) {
                throw new IllegalStateException("Embedding API вернуло неполный пакет");
            }
            return indexed.stream().map(IndexedVector::vector).toList();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось разобрать embeddings", exception);
        }
    }

    private String prefix(String value, boolean query) {
        String text = value == null ? "" : value.trim();
        if (model().toLowerCase().contains("nomic")) {
            return (query ? "search_query: " : "search_document: ") + text;
        }
        return text;
    }

    private String baseUrl() {
        return hasText(config.getEmbedding().getBaseUrl())
                ? config.getEmbedding().getBaseUrl() : config.getLlm().getBaseUrl();
    }

    private String apiKey() {
        if (hasText(config.getEmbedding().getApiKey())) return config.getEmbedding().getApiKey();
        return hasText(config.getLlm().getApiKey()) ? config.getLlm().getApiKey() : "lm-studio";
    }

    private String trimSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record IndexedVector(int index, float[] vector) {
    }

    private record CachedVector(float[] vector, long expiresAtMillis) {
    }
}
