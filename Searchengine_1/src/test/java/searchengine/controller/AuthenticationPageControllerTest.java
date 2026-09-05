package searchengine.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.ui.ConcurrentModel;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationPageControllerTest {

    private final AuthenticationPageController controller = new AuthenticationPageController(true);

    @Test
    void anonymousUserSeesLoginPage() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        ConcurrentModel model = new ConcurrentModel();
        assertThat(controller.login(anonymous, model)).isEqualTo("login");
        assertThat(model.getAttribute("registrationEnabled")).isEqualTo(true);
    }

    @Test
    void authenticatedUserIsRedirectedToSearchEngine() {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "user", "password", AuthorityUtils.createAuthorityList("ROLE_USER"));

        assertThat(controller.login(authentication, new ConcurrentModel())).isEqualTo("redirect:/app");
    }
}
