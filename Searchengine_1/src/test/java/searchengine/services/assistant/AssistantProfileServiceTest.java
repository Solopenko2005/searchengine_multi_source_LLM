package searchengine.services.assistant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.model.AssistantProfile;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.AssistantProfileRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.WorkspaceMembershipRepository;
import searchengine.services.CurrentUserService;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

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
        SiteRepository sites = mock(SiteRepository.class);
        Site aliceSource = source(10, "alice");
        when(profiles.findByOwnerId("alice")).thenReturn(Optional.empty());
        when(pages.findAccessiblePageIds(any(), any(), any(), anyBoolean())).thenReturn(List.of(1));
        when(pages.findAccessibleSiteIdsByPageIds(any(), any(), anyBoolean())).thenReturn(List.of(10));
        when(sites.findAccessibleByOwnerIds(any(), anyBoolean())).thenReturn(List.of(aliceSource));

        AssistantProfileService service = new AssistantProfileService(
                profiles, pages, sites, new CurrentUserService(mock(WorkspaceMembershipRepository.class)));
        AssistantProfileRequest request = new AssistantProfileRequest();
        request.setInstructions("Фокус на агрономии");
        request.setDocumentIds(List.of(1, 2));

        var response = service.save(request);
        assertThat(response.getDocumentIds()).containsExactly(1);
        assertThat(response.getSourceIds()).containsExactly(10);
        ArgumentCaptor<AssistantProfile> saved = ArgumentCaptor.forClass(AssistantProfile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo("alice");
        assertThat(saved.getValue().getDocumentIds()).isEqualTo("1");
        assertThat(saved.getValue().getSourceIds()).isEqualTo("10");
        verify(pages, never()).findBySiteIdsOrderByIdDesc(any());
        verify(pages, never()).findAllByOrderByIdDesc();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(username, "n/a", "ROLE_USER"));
    }

    private Site source(int siteId, String owner) {
        Site site = new Site();
        site.setId(siteId);
        site.setSourceType(SourceType.DOCUMENT);
        site.setOwnerId(owner);
        return site;
    }

    @Test
    void savesFortyThreeSourcesWithoutLoadingPageContent() {
        authenticate("alice");
        AssistantProfileRepository profiles = mock(AssistantProfileRepository.class);
        PageRepository pages = mock(PageRepository.class);
        SiteRepository sites = mock(SiteRepository.class);
        List<Integer> sourceIds = IntStream.rangeClosed(1, 43).boxed().toList();
        List<Site> accessibleSources = sourceIds.stream().map(id -> source(id, "alice")).toList();
        when(profiles.findByOwnerId("alice")).thenReturn(Optional.empty());
        when(sites.findAccessibleByOwnerIds(any(), anyBoolean())).thenReturn(accessibleSources);

        AssistantProfileService service = new AssistantProfileService(
                profiles, pages, sites, new CurrentUserService(mock(WorkspaceMembershipRepository.class)));
        AssistantProfileRequest request = new AssistantProfileRequest();
        request.setSourceIds(sourceIds);

        var response = service.save(request);

        assertThat(response.getSourceIds()).containsExactlyElementsOf(sourceIds);
        assertThat(response.getDocumentIds()).isEmpty();
        verify(pages, never()).findBySiteIdsOrderByIdDesc(any());
        verify(pages, never()).findAllByOrderByIdDesc();
        verify(pages, never()).findBySiteIdOrderByIdDesc(anyInt());
    }
}
