package searchengine.controller.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.assistant.AssistantChatResponse;
import searchengine.dto.assistant.ChatRequest;
import searchengine.dto.assistant.TopicsSummaryResponse;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.dto.assistant.AssistantProfileResponse;
import searchengine.dto.assistant.AssistantExportRequest;
import searchengine.services.assistant.AssistantService;
import searchengine.services.assistant.AssistantProfileService;
import searchengine.services.assistant.AssistantExportService;
import searchengine.services.assistant.AssistantStreamingService;
import searchengine.services.assistant.AssistantTopicJobService;
import searchengine.services.assistant.EmbeddingIndexCoordinator;
import searchengine.services.assistant.AssistantMetricsService;
import searchengine.services.CurrentUserService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * REST-контроллер LLM-ассистента.
 * <p>
 * Эндпоинты:
 *  - POST /api/assistant/chat   — вопрос по документам, ответ со ссылками на источники;
 *  - GET  /api/assistant/topics — обзор популярных тематик по документам;
 *  - GET  /api/assistant/status — настроена ли языковая модель.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantProfileService profileService;
    private final AssistantExportService exportService;
    private final AssistantStreamingService streamingService;
    private final AssistantTopicJobService topicJobService;
    private final EmbeddingIndexCoordinator embeddingIndexCoordinator;
    private final AssistantMetricsService metricsService;
    private final CurrentUserService currentUserService;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody ChatRequest request) {
        return assistantService.chat(request);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        return streamingService.stream(request);
    }

    @PostMapping("/chat/{requestId}/cancel")
    public Map<String, Object> cancel(@PathVariable String requestId) {
        return Map.of("result", true, "cancelled", streamingService.cancel(requestId));
    }

    @GetMapping("/topics")
    public TopicsSummaryResponse topics() {
        TopicsSummaryResponse response = assistantService.topics();
        boolean refreshing = topicJobService.isRunning();
        if (!refreshing && response.isStale()
                && (response.getTopics() == null || response.getTopics().isEmpty())) {
            refreshing = topicJobService.start();
        }
        response.setRefreshing(refreshing);
        return response;
    }

    @PostMapping("/topics/refresh")
    public Map<String, Object> refreshTopics() {
        boolean started = topicJobService.start();
        return Map.of("result", true, "accepted", started,
                "message", started ? "Анализ тематик запущен" : "Анализ тематик уже выполняется");
    }

    @GetMapping("/topics/status")
    public Map<String, Object> topicStatus() { return topicJobService.status(); }

    @GetMapping("/semantic/status")
    public Map<String, Object> semanticStatus() {
        return embeddingIndexCoordinator.status(profileService.resolve(java.util.List.of(), "").getSourceIds());
    }

    @PostMapping("/semantic/pause")
    public Map<String, Object> pauseSemanticIndex() {
        requireAdmin();
        embeddingIndexCoordinator.pause();
        return Map.of("result", true, "paused", true);
    }

    @PostMapping("/semantic/resume")
    public Map<String, Object> resumeSemanticIndex() {
        requireAdmin();
        embeddingIndexCoordinator.resume();
        return Map.of("result", true, "paused", false);
    }

    @PostMapping("/semantic/retry")
    public Map<String, Object> retrySemanticIndex() {
        requireAdmin();
        return Map.of("result", true, "reset", embeddingIndexCoordinator.retryFailed());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new HashMap<>(metricsService.snapshot());
        result.put("result", true);
        result.put("activeStreams", streamingService.activeCount());
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("result", true);
        map.put("llmConfigured", assistantService.isLlmConfigured());
        map.put("provider", assistantService.getLlmProvider());
        map.put("model", assistantService.getLlmModel());
        return map;
    }

    @GetMapping("/profile")
    public AssistantProfileResponse profile() {
        return profileService.get();
    }

    @PutMapping("/profile")
    public AssistantProfileResponse saveProfile(@RequestBody AssistantProfileRequest request) {
        return profileService.save(request);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "docx") String format,
                                         @RequestBody AssistantExportRequest request) {
        String normalized = format.toLowerCase();
        byte[] data = exportService.export(request, normalized);
        MediaType mediaType = "txt".equals(normalized)
                ? new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8)
                : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=assistant-report." + normalized)
                .contentType(mediaType)
                .body(data);
    }

    private void requireAdmin() {
        if (!currentUserService.isAdmin()) throw new SecurityException("Требуются права администратора");
    }
}
