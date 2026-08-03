package searchengine.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserServiceTest {

    private final CurrentUserService service = new CurrentUserService();

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
    void legacyDocumentWithoutOwnerIsAdminOnly() {
        authenticate("alice", "ROLE_USER");
        assertFalse(service.canAccess(document(null)));

        authenticate("admin", "ROLE_ADMIN");
        assertTrue(service.canAccess(document(null)));
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
