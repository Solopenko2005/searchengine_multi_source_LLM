package searchengine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import searchengine.services.auth.AuthServiceClient;
import searchengine.services.auth.AuthServiceException;

import java.util.List;

@Component
@ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "true")
public class ExternalAuthAuthenticationProvider implements AuthenticationProvider {

    private final AuthServiceClient authServiceClient;

    public ExternalAuthAuthenticationProvider(AuthServiceClient authServiceClient) {
        this.authServiceClient = authServiceClient;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        try {
            AuthServiceClient.AuthenticatedUser user = authServiceClient.authenticate(
                    authentication.getName(), String.valueOf(authentication.getCredentials()));
            List<SimpleGrantedAuthority> authorities = user.roles().stream()
                    .map(String::trim)
                    .filter(role -> !role.isBlank())
                    .map(String::toUpperCase)
                    .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            if (authorities.isEmpty()) {
                throw new BadCredentialsException("Для пользователя не назначена роль");
            }
            return new UsernamePasswordAuthenticationToken(user.email(), null, authorities);
        } catch (AuthServiceException exception) {
            throw new BadCredentialsException(exception.getMessage(), exception);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
