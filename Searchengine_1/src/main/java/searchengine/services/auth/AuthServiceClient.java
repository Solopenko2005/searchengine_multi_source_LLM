package searchengine.services.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "true")
public class AuthServiceClient {

    private final RestTemplate restTemplate;

    public AuthServiceClient(RestTemplateBuilder builder,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${app.security.auth-service-url:http://localhost:5555}") String serviceUrl) {
        this.restTemplate = builder
                .rootUri(serviceUrl)
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    public AuthenticatedUser authenticate(String email, String password) {
        try {
            AuthResponse response = restTemplate.postForObject(
                    "/api/v1/auth/login", new LoginRequest(email, password), AuthResponse.class);
            if (response == null || response.email() == null || response.roles() == null) {
                throw new AuthServiceException("Сервис авторизации вернул неполный ответ");
            }
            return new AuthenticatedUser(response.email(), response.roles());
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT
                    || exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AuthServiceException("Неверный e-mail или пароль", exception);
            }
            throw new AuthServiceException("Сервис авторизации временно недоступен", exception);
        } catch (ResourceAccessException exception) {
            throw new AuthServiceException("Не удалось подключиться к сервису авторизации", exception);
        }
    }

    public void register(RegistrationCommand command) {
        try {
            restTemplate.postForEntity("/api/v1/auth/register", new RegistrationRequest(
                    command.firstName(), command.lastName(), command.email(),
                    command.password(), command.confirmPassword(), ""), Void.class);
        } catch (HttpStatusCodeException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw new AuthServiceException("Пользователь с таким e-mail уже зарегистрирован", exception);
            }
            throw new AuthServiceException("Сервис авторизации временно недоступен", exception);
        } catch (ResourceAccessException exception) {
            throw new AuthServiceException("Не удалось подключиться к сервису авторизации", exception);
        }
    }

    public record RegistrationCommand(String firstName, String lastName, String email,
                                      String password, String confirmPassword) {
    }

    public record AuthenticatedUser(String email, Set<String> roles) {
    }

    private record LoginRequest(String email, String password) {
    }

    private record RegistrationRequest(String firstName, String lastName, String email,
                                       String password1, String password2, String captchaCode) {
    }

    private record AuthResponse(String accessToken, String refreshToken, String email, Set<String> roles) {
    }
}
