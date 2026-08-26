package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.config.assistant.AssistantConfig;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.PageRepository;
import searchengine.repository.WorkspaceMembershipRepository;
import searchengine.services.CurrentUserService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                new ObjectMapper(), new CurrentUserService(mock(WorkspaceMembershipRepository.class)));

        LlmTopicAnalysisService.Analysis analysis = service.analyze(List.of(10), "Агрономия")
                .orElseThrow();
        assertThat(analysis.getTopics()).hasSize(1);
        assertThat(analysis.getTopics().get(0).getTheme()).isEqualTo("Селекция");
        assertThat(analysis.getTopics().get(0).getConfidence()).isEqualTo(0.92);
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
}
