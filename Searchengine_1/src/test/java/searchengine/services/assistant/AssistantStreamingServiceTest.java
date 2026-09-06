package searchengine.services.assistant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.dto.assistant.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantStreamingServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aNewRequestCancelsTheQueuedRequestForTheSameUser() {
        AssistantService assistantService = mock(AssistantService.class);
        LlmClient llmClient = mock(LlmClient.class);
        AssistantMetricsService metricsService = mock(AssistantMetricsService.class);
        EmbeddingIndexCoordinator embeddingIndexCoordinator = mock(EmbeddingIndexCoordinator.class);
        List<Runnable> queuedTasks = new ArrayList<>();
        Executor executor = queuedTasks::add;
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        AssistantStreamingService service = new AssistantStreamingService(
                assistantService, llmClient, metricsService, embeddingIndexCoordinator,
                executor, scheduler);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "password", List.of()));

        service.stream(request("first"));
        service.stream(request("second"));

        assertThat(service.activeCount()).isEqualTo(1);
        assertThat(queuedTasks).hasSize(2);
        queuedTasks.get(0).run();
        verifyNoInteractions(assistantService, llmClient, metricsService, embeddingIndexCoordinator);
    }

    private ChatRequest request(String id) {
        ChatRequest request = new ChatRequest();
        request.setRequestId(id);
        request.setMessage("Вопрос");
        return request;
    }
}
