package searchengine.services.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import searchengine.config.assistant.AssistantConfig;
import searchengine.dto.assistant.ChatMessage;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Клиент OpenAI Responses API. Секрет читается только из конфигурации/переменной
 * окружения OPENAI_API_KEY и никогда не возвращается клиенту приложения.
 */
@Slf4j
@Component
public class LlmClient {

    private final AssistantConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final Semaphore generationSlots;
    private final Semaphore backgroundGenerationSlots;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger interactiveWaiters = new AtomicInteger();
    private final AtomicReference<Thread> backgroundGenerationThread = new AtomicReference<>();
    private final AtomicBoolean backgroundPreempted = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong circuitOpenUntilMillis = new AtomicLong();
    private final ConcurrentHashMap<String, CachedResponse> responseCache = new ConcurrentHashMap<>();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();

    public LlmClient(AssistantConfig config, ObjectMapper objectMapper,
                     WebClient.Builder webClientBuilder) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.generationSlots = new Semaphore(Math.max(1, config.getLlm().getMaxConcurrentRequests()), true);
        this.backgroundGenerationSlots = new Semaphore(1, true);
    }

    public boolean isConfigured() {
        AssistantConfig.Llm llm = config.getLlm();
        return llm.isEnabled()
                && hasText(llm.getApiKey())
                && hasText(llm.getBaseUrl())
                && hasText(llm.getModel());
    }

    public String getConfiguredModel() {
        return config.getLlm().getModel();
    }

    public boolean isLocalProvider() {
        return isLocalEndpoint(config.getLlm().getBaseUrl());
    }

    /** Выполняет обычную текстовую генерацию. */
    public String complete(List<ChatMessage> messages) {
        return execute(buildRequest(messages, null, null, null, false), false);
    }

    /**
     * Выполняет генерацию со Structured Outputs. Возвращаемая строка является
     * JSON, соответствующим переданной схеме.
     */
    public String completeJson(List<ChatMessage> messages, String schemaName, JsonNode schema) {
        return execute(buildRequest(messages, schemaName, schema, null, false), false);
    }

    /**
     * Structured generation for refreshable background data such as topic summaries.
     * It never queues ahead of a user answer and can be preempted by interactive chat.
     */
    public String completeJsonBackground(List<ChatMessage> messages, String schemaName, JsonNode schema) {
        int tokenLimit = Math.max(128, config.getLlm().getBackgroundMaxOutputTokens());
        return execute(buildRequest(messages, schemaName, schema, tokenLimit, true), true);
    }

    /** Streams visible output text deltas from an OpenAI-compatible Responses endpoint. */
    public Flux<String> stream(List<ChatMessage> messages) {
        if (!isConfigured()) return Flux.error(new LlmException("OpenAI API не настроен"));
        Map<String, Object> body = buildRequest(messages, null, null, null, false);
        body.put("stream", true);
        String requestCacheKey = cacheKey(body);
        AssistantConfig.Llm llm = config.getLlm();
        return Flux.defer(() -> {
                    String cached = cachedResponse(requestCacheKey);
                    if (cached != null) return Flux.just(cached);
                    acquireInteractiveGenerationSlot();
                    StringBuilder completedText = new StringBuilder();
                    WebClient webClient = webClient(MediaType.TEXT_EVENT_STREAM_VALUE, false);
                    return webClient.post().uri("/responses").bodyValue(body).retrieve()
                            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                            .handle((event, sink) -> {
                                String data = event.data();
                                if (data == null || data.isBlank() || "[DONE]".equals(data)) return;
                                try {
                                    JsonNode node = objectMapper.readTree(data);
                                    String type = node.path("type").asText();
                                    if ("response.output_text.delta".equals(type)) {
                                        String delta = node.path("delta").asText();
                                        if (!delta.isEmpty()) sink.next(delta);
                                    } else if ("response.failed".equals(type)) {
                                        sink.error(new LlmException(node.path("response").path("error")
                                                .path("message").asText("Генерация завершилась ошибкой")));
                                    }
                                } catch (Exception exception) {
                                    sink.error(new LlmException("Не удалось разобрать потоковый ответ", exception));
                                }
                            })
                            .cast(String.class)
                            .timeout(Duration.ofSeconds(Math.max(5, llm.getTimeoutSeconds())))
                            .doOnNext(completedText::append)
                            .doOnComplete(() -> {
                                recordProviderSuccess();
                                cacheResponse(requestCacheKey, completedText.toString());
                            })
                            .doOnError(this::recordProviderFailure)
                            .doFinally(signal -> releaseGenerationSlot(false));
                });
    }

    private Map<String, Object> buildRequest(List<ChatMessage> messages,
                                             String schemaName, JsonNode schema,
                                             Integer outputTokenLimit,
                                             boolean background) {
        AssistantConfig.Llm llm = config.getLlm();
        ProviderConfig provider = provider(background);
        boolean localEndpoint = isLocalEndpoint(provider.baseUrl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.model());
        body.put("store", false);
        int outputTokens = Math.max(128, llm.getMaxOutputTokens());
        if (localEndpoint) {
            // CPU-hosted models must start returning visible text quickly. A large
            // generation budget lets an abandoned request keep the model busy long
            // after the browser has gone away. Structured topic analysis still gets
            // more room because truncated JSON cannot be parsed.
            outputTokens = schema == null
                    ? Math.min(outputTokens, 256)
                    : Math.max(640, Math.min(outputTokens, 768));
        }
        if (outputTokenLimit != null) {
            outputTokens = Math.min(outputTokens, Math.max(128, outputTokenLimit));
        }
        body.put("max_output_tokens", outputTokens);
        safetyIdentifier().ifPresent(value -> body.put("safety_identifier", value));

        if (!localEndpoint && hasText(llm.getReasoningEffort())) {
            body.put("reasoning", Map.of("effort", llm.getReasoningEffort()));
        }

        List<Map<String, Object>> input = new ArrayList<>();
        StringBuilder instructions = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message == null || !hasText(message.getRole()) || message.getContent() == null) {
                continue;
            }
            if ("system".equals(message.getRole()) || "developer".equals(message.getRole())) {
                if (instructions.length() > 0) {
                    instructions.append("\n\n");
                }
                instructions.append(message.getContent());
            } else if ("user".equals(message.getRole()) || "assistant".equals(message.getRole())) {
                String content = message.getContent();
                if (localEndpoint && "user".equals(message.getRole()) && input.stream()
                        .noneMatch(item -> "user".equals(item.get("role")))) {
                    content = "/no_think\n" + content;
                }
                input.add(Map.of("role", message.getRole(), "content", content));
            }
        }
        if (schema != null && localEndpoint) {
            if (instructions.length() > 0) {
                instructions.append("\n\n");
            }
            instructions.append("Return only valid JSON matching this JSON Schema exactly. ")
                    .append("Do not add fields that are not declared in the schema:\n")
                    .append(schema);
        }
        if (instructions.length() > 0) {
            body.put("instructions", instructions.toString());
        }
        body.put("input", input);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("verbosity", "medium");
        if (schema != null && !localEndpoint) {
            Map<String, Object> format = new LinkedHashMap<>();
            format.put("type", "json_schema");
            format.put("name", hasText(schemaName) ? schemaName : "structured_result");
            format.put("strict", true);
            format.put("schema", schema);
            text.put("format", format);
        } else {
            // OpenAI-compatible local servers such as LM Studio require an
            // explicit format even for an ordinary text response.
            text.put("format", Map.of("type", "text"));
        }
        body.put("text", text);
        if (log.isDebugEnabled()) {
            int inputChars = messages.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(ChatMessage::getContent)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(String::length)
                    .sum();
            log.debug("LLM request model={}, local={}, inputChars={}, outputTokens={}, structured={}",
                    provider.model(), localEndpoint, inputChars, outputTokens, schema != null);
        }
        return body;
    }

    private boolean isLocalEndpoint(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host)
                    || "host.docker.internal".equalsIgnoreCase(host);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ProviderConfig provider(boolean background) {
        AssistantConfig.Llm llm = config.getLlm();
        if (!background || !hasText(llm.getBackgroundBaseUrl())) {
            return new ProviderConfig(llm.getBaseUrl(), llm.getApiKey(), llm.getModel());
        }
        return new ProviderConfig(
                llm.getBackgroundBaseUrl(),
                hasText(llm.getBackgroundApiKey()) ? llm.getBackgroundApiKey() : llm.getApiKey(),
                hasText(llm.getBackgroundModel()) ? llm.getBackgroundModel() : llm.getModel());
    }

    private boolean isBackgroundProviderIsolated() {
        AssistantConfig.Llm llm = config.getLlm();
        return hasText(llm.getBackgroundBaseUrl())
                && !trimTrailingSlash(llm.getBackgroundBaseUrl())
                .equalsIgnoreCase(trimTrailingSlash(llm.getBaseUrl()));
    }

    private String execute(Map<String, Object> body, boolean background) {
        if (!isConfigured()) {
            throw new LlmException("OpenAI API не настроен");
        }

        String cacheKey = cacheKey(body);
        String cached = cachedResponse(cacheKey);
        if (cached != null) return cached;

        if (background) {
            acquireBackgroundGenerationSlot();
        } else {
            acquireInteractiveGenerationSlot();
        }
        try {
            int timeoutSeconds = background
                    ? Math.min(config.getLlm().getTimeoutSeconds(),
                    Math.max(5, config.getLlm().getBackgroundTimeoutSeconds()))
                    : config.getLlm().getTimeoutSeconds();
            int attempts = background ? 1 : Math.max(1, config.getLlm().getRetryAttempts());
            String result = executeWithRetries(body, timeoutSeconds, attempts, background);
            if (!background) recordProviderSuccess();
            cacheResponse(cacheKey, result);
            return result;
        } catch (RuntimeException exception) {
            // Refreshable background work must never open the circuit for chat.
            if (!background) recordProviderFailure(exception);
            throw exception;
        } finally {
            if (background) {
                Thread current = Thread.currentThread();
                backgroundGenerationThread.compareAndSet(current, null);
                if (backgroundPreempted.getAndSet(false)) {
                    // Reactor restores the interrupt flag after cancelling block().
                    // Clear only the interrupt explicitly requested by an interactive call.
                    Thread.interrupted();
                }
            }
            releaseGenerationSlot(background);
        }
    }

    private String executeWithRetries(Map<String, Object> body, int timeoutSeconds, int attempts,
                                      boolean background) {
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                WebClient webClient = webClient(MediaType.APPLICATION_JSON_VALUE, background);

                String responseBody = webClient.post()
                        .uri("/responses")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(Math.max(5, timeoutSeconds)));

                return extractOutputText(responseBody);
            } catch (WebClientResponseException e) {
                lastError = new LlmException(safeProviderError(e), e);
                if (!isTransient(e.getRawStatusCode()) || attempt == attempts) {
                    throw lastError;
                }
                sleepBeforeRetry(attempt, e.getHeaders().getFirst("Retry-After"));
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new LlmException("Фоновый LLM-анализ уступил интерактивному запросу", e);
                }
                lastError = new LlmException("OpenAI API временно недоступен", e);
                if (attempt == attempts) {
                    throw lastError;
                }
                sleepBeforeRetry(attempt, null);
            }
        }
        throw lastError != null ? lastError : new LlmException("Не удалось получить ответ OpenAI API");
    }

    public Map<String, Object> runtimeStatus() {
        long now = System.currentTimeMillis();
        long openUntil = circuitOpenUntilMillis.get();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", isConfigured());
        status.put("provider", isLocalProvider() ? "local" : "remote");
        status.put("model", getConfiguredModel());
        status.put("circuit", openUntil > now ? "open" : "closed");
        status.put("circuitRetryAt", openUntil > now ? Instant.ofEpochMilli(openUntil).toString() : null);
        status.put("consecutiveFailures", consecutiveFailures.get());
        status.put("inFlight", inFlight.get());
        status.put("interactiveWaiters", interactiveWaiters.get());
        status.put("backgroundActive", backgroundGenerationThread.get() != null);
        status.put("backgroundIsolated", isBackgroundProviderIsolated());
        status.put("backgroundModel", provider(true).model());
        status.put("maxConcurrent", Math.max(1, config.getLlm().getMaxConcurrentRequests()));
        status.put("cacheEntries", responseCache.size());
        status.put("cacheHits", cacheHits.sum());
        status.put("cacheMisses", cacheMisses.sum());
        return status;
    }

    private WebClient webClient(String accept, boolean background) {
        ProviderConfig provider = provider(background);
        return webClientBuilder
                .baseUrl(trimTrailingSlash(provider.baseUrl()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, accept)
                .build();
    }

    private void assertCircuitAvailable() {
        long now = System.currentTimeMillis();
        long openUntil = circuitOpenUntilMillis.get();
        if (openUntil > now) {
            throw new LlmException("Языковая модель восстанавливается после ошибок; повторите запрос через "
                    + Math.max(1, (openUntil - now + 999) / 1000) + " с");
        }
        if (openUntil > 0) circuitOpenUntilMillis.compareAndSet(openUntil, 0);
    }

    private void acquireInteractiveGenerationSlot() {
        assertCircuitAvailable();
        interactiveWaiters.incrementAndGet();
        try {
            Thread background = !isBackgroundProviderIsolated() && generationSlots.availablePermits() == 0
                    ? backgroundGenerationThread.get() : null;
            long waitMillis = Math.max(0, config.getLlm().getQueueTimeoutMillis());
            if (background != null && background != Thread.currentThread()) {
                backgroundPreempted.set(true);
                background.interrupt();
                waitMillis = Math.max(waitMillis, 5_000L);
            }
            boolean acquired = generationSlots.tryAcquire(
                    waitMillis, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new LlmException("Языковая модель занята; повторите запрос через несколько секунд");
            }
            inFlight.incrementAndGet();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("Ожидание языковой модели прервано", exception);
        } finally {
            interactiveWaiters.decrementAndGet();
        }
    }

    private void acquireBackgroundGenerationSlot() {
        boolean isolated = isBackgroundProviderIsolated();
        if (!isolated) assertCircuitAvailable();
        Thread current = Thread.currentThread();
        if ((!isolated && interactiveWaiters.get() > 0)
                || !backgroundGenerationThread.compareAndSet(null, current)) {
            throw new LlmException("Фоновый LLM-анализ отложен: ассистент обрабатывает вопрос пользователя");
        }
        boolean acquired = false;
        try {
            Semaphore slots = isolated ? backgroundGenerationSlots : generationSlots;
            acquired = (isolated || interactiveWaiters.get() == 0) && slots.tryAcquire();
            if (!acquired) {
                throw new LlmException("Фоновый LLM-анализ отложен: языковая модель занята");
            }
            inFlight.incrementAndGet();
        } finally {
            if (!acquired) backgroundGenerationThread.compareAndSet(current, null);
        }
    }

    private void releaseGenerationSlot(boolean background) {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
        if (background && isBackgroundProviderIsolated()) {
            backgroundGenerationSlots.release();
        } else {
            generationSlots.release();
        }
    }

    private void recordProviderSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntilMillis.set(0);
    }

    private void recordProviderFailure(Throwable error) {
        if (error instanceof LlmException
                && error.getMessage() != null
                && (error.getMessage().contains("занята") || error.getMessage().contains("восстанавливается"))) {
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = Math.max(1, config.getLlm().getCircuitFailureThreshold());
        if (failures >= threshold) {
            circuitOpenUntilMillis.set(System.currentTimeMillis()
                    + Math.max(1, config.getLlm().getCircuitCooldownSeconds()) * 1000L);
        }
    }

    private String cachedResponse(String key) {
        CachedResponse cached = responseCache.get(key);
        if (cached == null) {
            cacheMisses.increment();
            return null;
        }
        if (cached.expiresAtMillis() <= System.currentTimeMillis()) {
            responseCache.remove(key, cached);
            cacheMisses.increment();
            return null;
        }
        cacheHits.increment();
        return cached.text();
    }

    private void cacheResponse(String key, String response) {
        int maxEntries = Math.max(0, config.getLlm().getResponseCacheMaxEntries());
        int ttlSeconds = Math.max(0, config.getLlm().getResponseCacheTtlSeconds());
        if (maxEntries == 0 || ttlSeconds == 0 || response == null || response.isBlank()) return;
        if (responseCache.size() >= maxEntries) {
            long now = System.currentTimeMillis();
            responseCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
            if (responseCache.size() >= maxEntries) {
                responseCache.keySet().stream().findAny().ifPresent(responseCache::remove);
            }
        }
        responseCache.put(key, new CachedResponse(response,
                System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    private String cacheKey(Map<String, Object> body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(body));
            StringBuilder key = new StringBuilder();
            for (byte value : digest) key.append(String.format("%02x", value));
            return key.toString();
        } catch (Exception exception) {
            throw new LlmException("Не удалось подготовить запрос к языковой модели", exception);
        }
    }

    private String extractOutputText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new LlmException("OpenAI API вернул ошибку: " +
                        error.path("message").asText("неизвестная ошибка"));
            }

            String status = root.path("status").asText();
            if ("failed".equals(status) || "incomplete".equals(status)) {
                String reason = root.path("incomplete_details").path("reason").asText(status);
                throw new LlmException("Генерация не завершена: " + reason);
            }

            // output_text существует в некоторых совместимых представлениях/SDK.
            if (root.hasNonNull("output_text") && !root.path("output_text").asText().isBlank()) {
                return root.path("output_text").asText().trim();
            }

            StringBuilder result = new StringBuilder();
            for (JsonNode outputItem : root.path("output")) {
                if (!"message".equals(outputItem.path("type").asText())) {
                    continue;
                }
                for (JsonNode content : outputItem.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        if (result.length() > 0) {
                            result.append('\n');
                        }
                        result.append(content.path("text").asText());
                    }
                }
            }
            if (result.length() == 0) {
                throw new LlmException("Ответ OpenAI не содержит output_text");
            }
            return result.toString().trim();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Не удалось разобрать ответ OpenAI API", e);
        }
    }

    private String safeProviderError(WebClientResponseException e) {
        try {
            JsonNode root = objectMapper.readTree(e.getResponseBodyAsString());
            String message = root.path("error").path("message").asText();
            if (hasText(message)) {
                return "OpenAI API: HTTP " + e.getRawStatusCode() + ": " + message;
            }
        } catch (Exception ignored) {
        }
        return "OpenAI API: HTTP " + e.getRawStatusCode();
    }

    private boolean isTransient(int status) {
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }

    private void sleepBeforeRetry(int attempt, String retryAfter) {
        long delayMillis = Math.min(8000L, 500L * (1L << Math.min(attempt - 1, 4)));
        if (hasText(retryAfter)) {
            try {
                delayMillis = Math.max(delayMillis, Long.parseLong(retryAfter) * 1000L);
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Ожидание повторного вызова OpenAI прервано", e);
        }
    }

    private String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/")
                ? value.substring(0, value.length() - 1)
                : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private java.util.Optional<String> safetyIdentifier() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !hasText(authentication.getName()) || "anonymousUser".equals(authentication.getName())) {
            return java.util.Optional.empty();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(authentication.getName().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("usr_");
            for (int i = 0; i < 16; i++) {
                value.append(String.format("%02x", digest[i]));
            }
            return java.util.Optional.of(value.toString());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record CachedResponse(String text, long expiresAtMillis) {
    }

    private record ProviderConfig(String baseUrl, String apiKey, String model) {
    }
}
