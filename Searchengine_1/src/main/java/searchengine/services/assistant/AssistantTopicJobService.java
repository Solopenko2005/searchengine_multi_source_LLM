package searchengine.services.assistant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import searchengine.services.CurrentUserService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Service
public class AssistantTopicJobService {
    private final AssistantService assistantService;
    private final CurrentUserService currentUserService;
    private final Executor executor;
    private final Map<String, JobState> states = new ConcurrentHashMap<>();

    public AssistantTopicJobService(AssistantService assistantService, CurrentUserService currentUserService,
                                    @Qualifier("assistantRequestExecutor") Executor executor) {
        this.assistantService = assistantService;
        this.currentUserService = currentUserService;
        this.executor = executor;
    }

    public boolean start() {
        String ownerId = currentUserService.getUserId();
        JobState existing = states.get(ownerId);
        if (existing != null && existing.running) return false;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        JobState state = new JobState();
        state.running = true;
        state.startedAt = LocalDateTime.now();
        states.put(ownerId, state);
        CompletableFuture.runAsync(() -> run(ownerId, authentication, state), executor);
        return true;
    }

    private void run(String ownerId, Authentication authentication, JobState state) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            assistantService.refreshTopics();
            state.error = null;
        } catch (Exception exception) {
            String message = exception.getMessage();
            state.error = message != null && message.contains("уступил интерактивному запросу")
                    ? "Обновление тем отложено, чтобы не задерживать ответ ассистента. Повторите анализ позже."
                    : message;
        } finally {
            state.running = false;
            state.finishedAt = LocalDateTime.now();
            states.put(ownerId, state);
            SecurityContextHolder.clearContext();
        }
    }

    public boolean isRunning() {
        JobState state = states.get(currentUserService.getUserId());
        return state != null && state.running;
    }

    public Map<String, Object> status() {
        JobState state = states.get(currentUserService.getUserId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", true);
        result.put("running", state != null && state.running);
        result.put("startedAt", state == null ? null : state.startedAt);
        result.put("finishedAt", state == null ? null : state.finishedAt);
        result.put("error", state == null ? null : state.error);
        return result;
    }

    private static class JobState {
        volatile boolean running;
        volatile String error;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime finishedAt;
    }
}
