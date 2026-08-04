package searchengine.services.assistant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.model.AssistantProfile;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.AssistantProfileRepository;
import searchengine.repository.PageRepository;
import searchengine.services.CurrentUserService;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantProfileServiceTest {

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void savesOnlyDocumentsOwnedByCurrentUser() {
        authenticate("alice");
        AssistantProfileRepository profiles = mock(AssistantProfileRepository.class);
        PageRepository pages = mock(PageRepository.class);
        when(profiles.findByOwnerId("alice")).thenReturn(Optional.empty());
        when(pages.findBySiteSourceTypeOrderByIdDesc(SourceType.DOCUMENT))
                .thenReturn(List.of(document(1, "alice"), document(2, "bob")));

        AssistantProfileService service = new AssistantProfileService(
                profiles, pages, new CurrentUserService());
        AssistantProfileRequest request = new AssistantProfileRequest();
        request.setInstructions("Фокус на агрономии");
        request.setDocumentIds(List.of(1, 2));

        assertThat(service.save(request).getDocumentIds()).containsExactly(1);
        ArgumentCaptor<AssistantProfile> saved = ArgumentCaptor.forClass(AssistantProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("alice");
        assertThat(saved.getValue().getDocumentIds()).isEqualTo("1");
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(username, "n/a", "ROLE_USER"));
    }

    private Page document(int id, String owner) {
        Site site = new Site();
        site.setSourceType(SourceType.DOCUMENT);
        Page page = new Page();
        page.setId(id);
        page.setSite(site);
        page.setOwnerId(owner);
        return page;
    }
}
