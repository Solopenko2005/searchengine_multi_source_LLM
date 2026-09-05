package searchengine.services.assistant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import lombok.extern.slf4j.Slf4j;
import searchengine.dto.assistant.ChatRequest;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

@Service
@Slf4j
public class AssistantStreamingService {
    private static final long STREAM_TIMEOUT_MILLIS = 185_000L;
    private final AssistantService assistantService;
    private final LlmClient llmClient;
    private final AssistantMetricsService metricsService;
    private final Executor executor;
    private final Map<String, ActiveRequest> active = new ConcurrentHashMap<>();

    public AssistantStreamingService(AssistantService assistantService, LlmClient llmClient,
                                     AssistantMetricsService metricsService,
                                     @Qualifier("assistantRequestExecutor") Executor executor) {
        this.assistantService = assistantService;
        this.llmClient = llmClient;
        this.metricsService = metricsService;
        this.executor = executor;
    }

    public SseEmitter stream(ChatRequest request) {
        String requestId = validRequestId(request == null ? null : request.getRequestId());
        if (request != null) request.setRequestId(requestId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String ownerId = authentication == null ? "" : authentication.getName();
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(requestId, request, authentication, emitter);
            return null;
        });
        active.put(requestId, new ActiveRequest(task, ownerId));
        emitter.onCompletion(() -> remove(requestId, false));
        emitter.onTimeout(() -> remove(requestId, true));
        emitter.onError(error -> remove(requestId, true));
        executor.execute(task);
        return emitter;
    }

    private void execute(String requestId, ChatRequest request, Authentication authentication,
                         SseEmitter emitter) {
        long startedAt = System.nanoTime();
        long generationMs = 0;
        AssistantService.StreamPreparation preparation = null;
        boolean failed = false;
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            send(emitter, "progress", Map.of("stage", "retrieval", "message", "Ищу релевантные фрагменты"));
            preparation = assistantService.prepareStream(request);
            send(emitter, "sources", Map.of("items", preparation.getSources(),
                    "retrievalMs", preparation.getRetrievalMs(),
                    "retrievalMode", preparation.getRetrievalMode()));
            if (preparation.getImmediateAnswer() != null) {
                send(emitter, "delta", Map.of("text", preparation.getImmediateAnswer()));
                sendDone(emitter, requestId, false, preparation.getRetrievalMs(), 0,
                        elapsedMillis(startedAt), preparation.getRetrievalMode());
                return;
            }

            send(emitter, "progress", Map.of("stage", "generation", "message", "Формирую ответ"));
            long generationStartedAt = System.nanoTime();
            StringBuilder answer = new StringBuilder();
            for (String delta : llmClient.stream(preparation.getMessages()).toIterable()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                answer.append(delta);
                send(emitter, "delta", Map.of("text", delta));
            }
            generationMs = elapsedMillis(generationStartedAt);
            sendDone(emitter, requestId, true, preparation.getRetrievalMs(), generationMs,
                    elapsedMillis(startedAt), preparation.getRetrievalMode());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            safeSend(emitter, "cancelled", Map.of("requestId", requestId));
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted() || causedByInterruption(exception)) {
                safeSend(emitter, "cancelled", Map.of("requestId", requestId));
                return;
            }
            failed = true;
            log.warn("Потоковый запрос LLM {} завершился ошибкой: {}",
                    requestId, safeMessage(exception), exception);
            if (preparation != null && preparation.getFallbackAnswer() != null) {
                safeSend(emitter, "delta", Map.of("text", preparation.getFallbackAnswer()));
            }
            safeSend(emitter, "error", Map.of("message", safeMessage(exception)));
        } finally {
            long retrievalMs = preparation == null ? 0 : preparation.getRetrievalMs();
            metricsService.record(retrievalMs, generationMs, elapsedMillis(startedAt), failed);
            active.remove(requestId);
            try { emitter.complete(); } catch (Exception ignored) { }
            SecurityContextHolder.clearContext();
        }
    }

    private void sendDone(SseEmitter emitter, String requestId, boolean usedLlm, long retrievalMs,
                          long generationMs, long totalMs, String mode) throws IOException {
        send(emitter, "done", Map.of("requestId", requestId, "usedLlm", usedLlm,
                "retrievalMs", retrievalMs, "generationMs", generationMs,
                "totalMs", totalMs, "retrievalMode", mode == null ? "none" : mode));
    }

    public boolean cancel(String requestId) {
        ActiveRequest request = active.get(requestId);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String ownerId = authentication == null ? "" : authentication.getName();
        if (request == null || !request.ownerId.equals(ownerId)) return false;
        active.remove(requestId, request);
        return request.task.cancel(true);
    }

    public int activeCount() { return active.size(); }

    private void remove(String requestId, boolean cancel) {
        ActiveRequest request = active.remove(requestId);
        if (cancel && request != null) request.task.cancel(true);
    }

    private void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    private void safeSend(SseEmitter emitter, String name, Object data) {
        try { send(emitter, name, data); } catch (Exception ignored) { }
    }

    private String validRequestId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,80}") ? value : UUID.randomUUID().toString();
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "Не удалось сформировать ответ" : value;
    }

    private boolean causedByInterruption(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) return true;
            current = current.getCause();
        }
        return false;
    }

    private record ActiveRequest(FutureTask<Void> task, String ownerId) {
    }
}
