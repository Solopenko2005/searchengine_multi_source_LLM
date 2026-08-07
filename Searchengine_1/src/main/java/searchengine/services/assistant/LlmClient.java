package searchengine.services.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import searchengine.config.assistant.AssistantConfig;
import searchengine.dto.assistant.ChatMessage;

import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public LlmClient(AssistantConfig config, ObjectMapper objectMapper,
                     WebClient.Builder webClientBuilder) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
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

    /** Выполняет обычную текстовую генерацию. */
    public String complete(List<ChatMessage> messages) {
        return execute(buildRequest(messages, null, null));
    }

    /**
     * Выполняет генерацию со Structured Outputs. Возвращаемая строка является
     * JSON, соответствующим переданной схеме.
     */
    public String completeJson(List<ChatMessage> messages, String schemaName, JsonNode schema) {
        return execute(buildRequest(messages, schemaName, schema));
    }

    private Map<String, Object> buildRequest(List<ChatMessage> messages,
                                             String schemaName, JsonNode schema) {
        AssistantConfig.Llm llm = config.getLlm();
        boolean localEndpoint = isLocalEndpoint(llm.getBaseUrl());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.getModel());
        body.put("store", false);
        int outputTokens = Math.max(128, llm.getMaxOutputTokens());
        if (localEndpoint) outputTokens = Math.min(outputTokens, 700);
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
        return body;
    }

    private boolean isLocalEndpoint(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String execute(Map<String, Object> body) {
        if (!isConfigured()) {
            throw new LlmException("OpenAI API не настроен");
        }

        AssistantConfig.Llm llm = config.getLlm();
        int attempts = Math.max(1, llm.getRetryAttempts());
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                WebClient webClient = webClientBuilder
                        .baseUrl(trimTrailingSlash(llm.getBaseUrl()))
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + llm.getApiKey())
                        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .build();

                String responseBody = webClient.post()
                        .uri("/responses")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(Math.max(5, llm.getTimeoutSeconds())));

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
                lastError = new LlmException("OpenAI API временно недоступен", e);
                if (attempt == attempts) {
                    throw lastError;
                }
                sleepBeforeRetry(attempt, null);
            }
        }
        throw lastError != null ? lastError : new LlmException("Не удалось получить ответ OpenAI API");
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
                return "OpenAI API: HTTP " + e.getRawStatusCode() + " — " + message;
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
}
