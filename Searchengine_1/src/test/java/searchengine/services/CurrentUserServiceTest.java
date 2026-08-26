package searchengine.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.model.WorkspaceGroup;
import searchengine.model.WorkspaceMembership;
import searchengine.repository.WorkspaceMembershipRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {

    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final CurrentUserService service = new CurrentUserService(memberships);

    CurrentUserServiceTest() {
        when(memberships.findByUserIdOrderByJoinedAtAsc(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyOwnerCanAccessOwnedDocument() {
        authenticate("alice", "ROLE_USER");
        assertTrue(service.canAccess(document("alice")));
        assertFalse(service.canAccess(document("bob")));
    }

    @Test
    void administratorCannotAccessAnotherAdministratorsOwnedDocument() {
        authenticate("alice", "ROLE_ADMIN");
        assertTrue(service.canAccess(document("alice")));
        assertFalse(service.canAccess(document("bob")));
    }

    @Test
    void legacyDocumentWithoutOwnerIsAdminOnly() {
        authenticate("alice", "ROLE_USER");
        assertFalse(service.canAccess(document(null)));

        authenticate("admin", "ROLE_ADMIN");
        assertTrue(service.canAccess(document(null)));
    }

    @Test
    void groupMemberCanAccessAdministratorsSources() {
        authenticate("student@example.com", "ROLE_USER");
        WorkspaceGroup group = new WorkspaceGroup();
        group.setOwnerId("teacher@example.com");
        WorkspaceMembership membership = new WorkspaceMembership();
        membership.setGroup(group);
        membership.setUserId("student@example.com");
        when(memberships.findByUserIdOrderByJoinedAtAsc("student@example.com"))
                .thenReturn(List.of(membership));

        Site site = new Site();
        site.setOwnerId("teacher@example.com");

        assertTrue(service.canAccess(site));
    }

    private void authenticate(String name, String role) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                name, "n/a", List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Page document(String ownerId) {
        Site site = new Site();
        site.setSourceType(SourceType.DOCUMENT);
        Page page = new Page();
        page.setSite(site);
        page.setOwnerId(ownerId);
        return page;
    }
}
