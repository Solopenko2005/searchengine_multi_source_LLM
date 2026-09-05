package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.config.assistant.AssistantConfig;
import searchengine.model.Page;
import searchengine.model.AssistantChunk;
import searchengine.model.AssistantChunkStatus;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.PageRepository;
import searchengine.repository.AssistantChunkRepository;
import searchengine.repository.WorkspaceMembershipRepository;
import searchengine.services.CurrentUserService;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import searchengine.dto.assistant.ChatMessage;

class LlmTopicAnalysisServiceTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsTopicsBelowConfiguredConfidence() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        LlmClient client = mock(LlmClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.completeJson(any(), anyString(), any())).thenReturn("""
                {"summary":"Обзор","topics":[
                  {"theme":"Селекция","description":"Методы отбора","confidence":0.92,"documentIndexes":[1]},
                  {"theme":"Случайная тема","description":"Слабое основание","confidence":0.30,"documentIndexes":[1]}
                ]}
                """);

        PageRepository pages = mock(PageRepository.class);
        when(pages.findRecentAccessibleBySiteIds(any(), any(), any(Boolean.class), any()))
                .thenReturn(List.of(document()));
        AssistantConfig config = new AssistantConfig();
        config.getRag().setTopicMinConfidence(0.65);
        LlmTopicAnalysisService service = new LlmTopicAnalysisService(client, config, pages,
                new ObjectMapper(), new CurrentUserService(mock(WorkspaceMembershipRepository.class)),
                mock(AssistantChunkRepository.class));

        LlmTopicAnalysisService.Analysis analysis = service.analyze(List.of(10), "Агрономия")
                .orElseThrow();
        assertThat(analysis.getTopics()).hasSize(1);
        assertThat(analysis.getTopics().get(0).getTheme()).isEqualTo("Селекция");
        assertThat(analysis.getTopics().get(0).getConfidence()).isEqualTo(0.92);
    }

    @Test
    void prefersResearchContentOverPublishingMetadata() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        LlmClient client = mock(LlmClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.isLocalProvider()).thenReturn(true);
        when(client.completeJson(any(), anyString(), any())).thenReturn("""
                {"summary":"Обзор","topics":[
                  {"theme":"Селекция пшеницы","description":"Методы отбора","confidence":0.94,"documentIndexes":[1]}
                ]}
                """);
        AssistantChunkRepository chunks = mock(AssistantChunkRepository.class);
        when(chunks.findRepresentativeReadyIds(any(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(1L, 2L));
        AssistantChunk metadata = chunk(1L, "ISSN 1234. Для цитирования. Свидетельство о регистрации издания.");
        AssistantChunk research = chunk(2L, "Цель исследования: оценить методы селекции пшеницы. " +
                "Материалы и методы включали полевой эксперимент. Результаты показали устойчивость сортов.");
        when(chunks.findReadyWithPageByIds(any(), any(AssistantChunkStatus.class)))
                .thenReturn(List.of(metadata, research));
        AssistantConfig config = new AssistantConfig();
        config.getRag().setTopicDocumentLimit(1);
        LlmTopicAnalysisService service = new LlmTopicAnalysisService(client, config, mock(PageRepository.class),
                new ObjectMapper(), new CurrentUserService(mock(WorkspaceMembershipRepository.class)), chunks);

        service.analyze(List.of(10), "Агрономия").orElseThrow();

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).completeJson(messages.capture(), anyString(), any());
        String prompt = messages.getValue().get(1).getContent();
        assertThat(prompt).contains("методы селекции пшеницы");
        assertThat(prompt).doesNotContain("Свидетельство о регистрации издания");
    }

    @Test
    void localTopicPromptFitsSmallContextWindow() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "n/a", "ROLE_USER"));
        LlmClient client = mock(LlmClient.class);
        when(client.isConfigured()).thenReturn(true);
        when(client.isLocalProvider()).thenReturn(true);
        when(client.completeJson(any(), anyString(), any())).thenReturn("""
                {"summary":"Обзор","topics":[
                  {"theme":"Машинное обучение","description":"Методы анализа","confidence":0.9,"documentIndexes":[1]}
                ]}
                """);
        AssistantChunkRepository chunks = mock(AssistantChunkRepository.class);
        List<Long> ids = LongStream.rangeClosed(1, 40).boxed().toList();
        when(chunks.findRepresentativeReadyIds(any(), any(Integer.class), any(Integer.class)))
                .thenReturn(ids);
        List<AssistantChunk> candidates = ids.stream()
                .map(id -> chunk(id, id.intValue(), "Цель исследования и методы машинного обучения. ".repeat(40)))
                .toList();
        when(chunks.findReadyWithPageByIds(any(), any(AssistantChunkStatus.class)))
                .thenReturn(candidates);
        AssistantConfig config = new AssistantConfig();
        config.getRag().setTopicDocumentLimit(80);
        LlmTopicAnalysisService service = new LlmTopicAnalysisService(client, config, mock(PageRepository.class),
                new ObjectMapper(), new CurrentUserService(mock(WorkspaceMembershipRepository.class)), chunks);

        service.analyze(ids.stream().map(Long::intValue).toList(), "Анализ научных исследований").orElseThrow();

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(client).completeJson(messages.capture(), anyString(), any());
        String prompt = messages.getValue().get(1).getContent();
        assertThat(prompt.length()).isLessThan(8_500);
        assertThat(prompt).contains("document id=\"D14\"");
        assertThat(prompt).doesNotContain("document id=\"D15\"");
    }

    private Page document() {
        Site site = new Site();
        site.setId(10);
        site.setName("Мои документы");
        site.setSourceType(SourceType.DOCUMENT);
        Page page = new Page();
        page.setId(1);
        page.setSite(site);
        page.setOwnerId("alice");
        page.setOriginalFileName("selection.pdf");
        page.setContent("<html><body>Методы селекции и отбора растений</body></html>");
        return page;
    }

    private AssistantChunk chunk(long id, String content) {
        return chunk(id, 10, content);
    }

    private AssistantChunk chunk(long id, int siteId, String content) {
        Page page = document();
        page.setId((int) id);
        page.getSite().setId(siteId);
        page.setContent("<html><body>" + content + "</body></html>");
        AssistantChunk chunk = new AssistantChunk();
        chunk.setId(id);
        chunk.setSiteId(siteId);
        chunk.setPage(page);
        chunk.setContent(content);
        chunk.setChunkIndex((int) id - 1);
        chunk.setStatus(AssistantChunkStatus.READY);
        return chunk;
    }
}
