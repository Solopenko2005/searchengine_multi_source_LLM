package searchengine.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import searchengine.services.WorkspaceGroupService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceGroupControllerTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void invitationReturnsToApplicationAfterAuthentication() {
        WorkspaceGroupService groups = mock(WorkspaceGroupService.class);
        when(groups.create("Лаборатория")).thenReturn(Map.of(
                "id", 5L, "name", "Лаборатория", "inviteCode", "invite-123"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("search.example.test");
        request.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Map<String, Object> result = new WorkspaceGroupController(groups)
                .create(Map.of("name", "Лаборатория"));

        assertThat(result.get("inviteUrl"))
                .isEqualTo("https://search.example.test/app?join=invite-123");
    }
}
