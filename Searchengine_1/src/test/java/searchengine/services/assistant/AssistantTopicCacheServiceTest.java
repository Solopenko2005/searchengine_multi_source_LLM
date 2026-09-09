package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import searchengine.dto.assistant.TopicItem;
import searchengine.dto.assistant.TopicsSummaryResponse;
import searchengine.model.AssistantTopicCache;
import searchengine.repository.AssistantTopicCacheRepository;
import searchengine.repository.PageRepository;
import searchengine.services.CurrentUserService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantTopicCacheServiceTest {
    @Test
    void hashesScopeAndRoundTripsCachedTopics() throws Exception {
        AssistantTopicCacheRepository repository = mock(AssistantTopicCacheRepository.class);
        PageRepository pages = mock(PageRepository.class);
        CurrentUserService users = mock(CurrentUserService.class);
        LlmClient llm = mock(LlmClient.class);
        when(users.getUserId()).thenReturn("alice");
        when(llm.getConfiguredModel()).thenReturn("qwen-test");
        PageRepository.ScopeRevision revision = mock(PageRepository.ScopeRevision.class);
        when(revision.getPageCount()).thenReturn(12L);
        when(revision.getMaxPageId()).thenReturn(99L);
        when(revision.getSumPageIds()).thenReturn(400L);
        when(pages.scopeRevision(List.of(2, 7))).thenReturn(revision);
        AssistantTopicCacheService service = new AssistantTopicCacheService(repository, pages, users,
                llm, new ObjectMapper());
        String hash = service.scopeHash(List.of(7, 2), "Агрономия");
        TopicsSummaryResponse response = new TopicsSummaryResponse();
        response.setResult(true);
        response.setSummary("Темы");
        response.setTopics(List.of(new TopicItem(1, "Машинное обучение", 2, 2, List.of())));
        when(repository.findById("alice")).thenReturn(Optional.empty());

        service.save(hash, response);

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(cache ->
                cache.getOwnerId().equals("alice") && cache.getScopeHash().equals(hash)
                        && cache.getPayload().contains("Машинное обучение")));
        AssistantTopicCache stored = new AssistantTopicCache();
        stored.setOwnerId("alice");
        stored.setScopeHash(hash);
        stored.setModel("qwen-test|topic-analysis-v3-source-balanced");
        stored.setPayload(new ObjectMapper().writeValueAsString(response));
        stored.setUpdatedAt(LocalDateTime.now());
        when(repository.findById("alice")).thenReturn(Optional.of(stored));

        TopicsSummaryResponse restored = service.read(hash).orElseThrow();
        assertThat(restored.isCached()).isTrue();
        assertThat(restored.getTopics()).extracting(TopicItem::getTheme)
                .containsExactly("Машинное обучение");
        assertThat(service.read("another-hash")).isEmpty();
    }
}
