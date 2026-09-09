package searchengine.services.assistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import searchengine.config.assistant.AssistantConfig;
import searchengine.dto.assistant.AssistantChatResponse;
import searchengine.dto.assistant.ChatMessage;
import searchengine.dto.assistant.ChatRequest;
import searchengine.dto.assistant.TopicItem;
import searchengine.dto.assistant.TopicsSummaryResponse;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.AssistantChunkRepository;
import searchengine.repository.IndexRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;
import searchengine.services.CurrentUserService;
import searchengine.services.EnhancedTopicFilterService;
import searchengine.services.Lemmatizer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {
    private AssistantConfig config;
    private LlmClient llm;
    private Lemmatizer lemmatizer;
    private IndexRepository indexRepository;
    private PageRepository pageRepository;
    private LlmTopicAnalysisService topicAnalysis;
    private CurrentUserService currentUser;
    private AssistantProfileService profileService;
    private EmbeddingClient embeddingClient;
    private LocalVectorIndexService vectorIndex;
    private AssistantMetricsService metrics;
    private AssistantTopicCacheService topicCache;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        config = new AssistantConfig();
        config.getRag().setMaxDocuments(2);
        config.getRag().setMaxDocumentsPerSource(1);
        llm = mock(LlmClient.class);
        lemmatizer = mock(Lemmatizer.class);
        indexRepository = mock(IndexRepository.class);
        pageRepository = mock(PageRepository.class);
        topicAnalysis = mock(LlmTopicAnalysisService.class);
        currentUser = mock(CurrentUserService.class);
        profileService = mock(AssistantProfileService.class);
        embeddingClient = mock(EmbeddingClient.class);
        vectorIndex = mock(LocalVectorIndexService.class);
        metrics = mock(AssistantMetricsService.class);
        topicCache = mock(AssistantTopicCacheService.class);
        when(profileService.resolve(anyList(), anyList(), any()))
                .thenReturn(new AssistantProfileService.ResolvedProfile("Агрономия",
                        List.of(10, 20), List.of()));
        when(lemmatizer.getQueryLemmas(anyString())).thenReturn(Map.of("обучение", 1));
        when(currentUser.canAccess(any(Page.class))).thenReturn(true);
        when(currentUser.accessibleOwnerIds()).thenReturn(Set.of("alice"));
        when(topicCache.scopeHash(anyList(), anyString())).thenReturn("scope");
        service = new AssistantService(config, llm, lemmatizer, indexRepository, pageRepository,
                mock(SiteRepository.class), mock(EnhancedTopicFilterService.class),
                mock(TopicRepository.class), topicAnalysis, currentUser, profileService,
                mock(AssistantChunkRepository.class), embeddingClient, vectorIndex, metrics, topicCache);
    }

    @Test
    void validatesMissingBlankAndOversizedQuestions() {
        assertThat(service.chat(null).isResult()).isFalse();
        ChatRequest blank = request("   ");
        assertThat(service.chat(blank).getError()).contains("пустым");
        ChatRequest oversized = request("я".repeat(10_001));
        assertThat(service.chat(oversized).getError()).contains("слишком длинный");
    }

    @Test
    void returnsImmediateMessageWhenRetrievalFindsNothing() {
        when(indexRepository.findCandidatePageIdsByLemmasAndSiteIds(any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());
        when(pageRepository.findRecentAccessibleBySiteIds(any(), any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(List.of());

        AssistantChatResponse response = service.chat(request("Что найдено?"));

        assertThat(response.isResult()).isTrue();
        assertThat(response.isUsedLlm()).isFalse();
        assertThat(response.getAnswer()).contains("не нашлось информации");
        assertThat(response.getSources()).isEmpty();
    }

    @Test
    void balancesRagContextAcrossSourcesAndReturnsLlmAnswer() {
        arrangeRankedPages();
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyList())).thenReturn("Вывод подтверждён [1] и [2].");

        AssistantChatResponse response = service.chat(request("Машинное обучение в сельском хозяйстве"));

        assertThat(response.isResult()).isTrue();
        assertThat(response.isUsedLlm()).isTrue();
        assertThat(response.getRetrievalMode()).isEqualTo("lexical");
        assertThat(response.getSources()).extracting(source -> source.getSource())
                .containsExactly("Источник A", "Источник B");
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(messages.capture());
        String context = messages.getValue().get(messages.getValue().size() - 1).getContent();
        assertThat(context).contains("Статья A1", "Статья B1").doesNotContain("Статья A2");
    }

    @Test
    void fallsBackToRetrievedSourcesWhenProviderFails() {
        arrangeRankedPages();
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyList())).thenThrow(new LlmClient.LlmException("provider unavailable"));

        AssistantChatResponse response = service.chat(request("Машинное обучение"));

        assertThat(response.isResult()).isTrue();
        assertThat(response.isUsedLlm()).isFalse();
        assertThat(response.getAnswer()).contains("Языковая модель временно недоступна", "Статья A1");
        verify(metrics).record(anyLong(), anyLong(), anyLong(), anyLong(),
                org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true));
    }

    @Test
    void preparesStreamingPromptAndFallbackFromOneRetrieval() {
        arrangeRankedPages();
        when(llm.isConfigured()).thenReturn(true);

        AssistantService.StreamPreparation prepared = service.prepareStream(request("Методы обучения"));

        assertThat(prepared.getImmediateAnswer()).isNull();
        assertThat(prepared.getFallbackAnswer()).contains("Статья A1");
        assertThat(prepared.getMessages()).hasSize(2);
        assertThat(prepared.getSources()).hasSize(2);
        assertThat(prepared.getRetrievalMode()).isEqualTo("lexical");
    }

    @Test
    void returnsCachedTopicsAndPersistsFreshLlmAnalysis() {
        TopicsSummaryResponse cached = new TopicsSummaryResponse();
        cached.setResult(true);
        cached.setSummary("Кэш");
        when(profileService.resolve(anyList(), anyString()))
                .thenReturn(new AssistantProfileService.ResolvedProfile("Агрономия",
                        List.of(10), List.of()));
        when(topicCache.read("scope")).thenReturn(Optional.of(cached));
        assertThat(service.topics()).isSameAs(cached);

        when(llm.isConfigured()).thenReturn(true);
        TopicItem topic = new TopicItem(1, "Прогнозирование урожайности", 2, 2, List.of("Источник A"));
        when(topicAnalysis.analyze(List.of(10), "Агрономия"))
                .thenReturn(Optional.of(new LlmTopicAnalysisService.Analysis("Основная тема", List.of(topic))));
        TopicsSummaryResponse refreshed = service.refreshTopics();

        assertThat(refreshed.isResult()).isTrue();
        assertThat(refreshed.isUsedLlm()).isTrue();
        assertThat(refreshed.getTopics()).extracting(TopicItem::getTheme)
                .containsExactly("Прогнозирование урожайности");
        verify(profileService).saveDetectedTopics(List.of(topic));
        verify(topicCache).save("scope", refreshed);
    }

    private void arrangeRankedPages() {
        Page a1 = page(1, 10, "Источник A", "Статья A1", "Методы машинного обучения применяются для прогноза.");
        Page a2 = page(2, 10, "Источник A", "Статья A2", "Дополнительный материал машинного обучения.");
        Page b1 = page(3, 20, "Источник B", "Статья B1", "Сельское хозяйство использует модели прогноза.");
        when(indexRepository.findCandidatePageIdsByLemmasAndSiteIds(any(), any(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1, 2, 3));
        when(pageRepository.findAllById(any())).thenReturn(List.of(a1, a2, b1));
        when(embeddingClient.isConfigured()).thenReturn(false);
        when(llm.isLocalProvider()).thenReturn(false);
    }

    private ChatRequest request(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        return request;
    }

    private Page page(int id, int siteId, String siteName, String title, String text) {
        Site site = new Site();
        site.setId(siteId);
        site.setName(siteName);
        site.setUrl("https://example.test/" + siteId);
        site.setSourceType(SourceType.WEBSITE);
        Page page = new Page();
        page.setId(id);
        page.setSite(site);
        page.setPath("/article-" + id);
        page.setOriginalFileName(title);
        page.setContent("<html><head><title>" + title + "</title></head><body>" + text + "</body></html>");
        return page;
    }
}
