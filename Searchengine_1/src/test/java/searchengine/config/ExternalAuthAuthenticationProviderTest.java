package searchengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import searchengine.services.auth.AuthServiceClient;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalAuthAuthenticationProviderTest {

    @Test
    void mapsAdministratorRoleFromAuthorizationService() {
        AuthServiceClient client = mock(AuthServiceClient.class);
        when(client.authenticate("admin@example.com", "password"))
                .thenReturn(new AuthServiceClient.AuthenticatedUser(
                        "admin@example.com", Set.of("ADMIN")));
        ExternalAuthAuthenticationProvider provider = new ExternalAuthAuthenticationProvider(client);

        Authentication result = provider.authenticate(
                new UsernamePasswordAuthenticationToken("admin@example.com", "password"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getName()).isEqualTo("admin@example.com");
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }
}
