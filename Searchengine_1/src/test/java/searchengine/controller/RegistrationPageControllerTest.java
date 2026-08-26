package searchengine.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import searchengine.services.auth.AuthServiceClient;
import searchengine.services.auth.AuthServiceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RegistrationPageControllerTest {

    private AuthServiceClient client;
    private RegistrationPageController controller;

    @BeforeEach
    void setUp() {
        client = mock(AuthServiceClient.class);
        controller = new RegistrationPageController(client);
    }

    @Test
    void validRegistrationRedirectsToLogin() {
        String view = controller.register("Иван", "Иванов", "USER@EXAMPLE.COM",
                "password1", "password1", "USER", new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/login?registered");
        verify(client).register(new AuthServiceClient.RegistrationCommand(
                "Иван", "Иванов", "user@example.com", "password1", "password1", "USER"));
    }

    @Test
    void passwordMismatchReturnsRegistrationPage() {
        ConcurrentModel model = new ConcurrentModel();
        String view = controller.register("Иван", "Иванов", "user@example.com",
                "password1", "password2", "USER", model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("registrationError")).isEqualTo("Пароли не совпадают");
        verify(client, never()).register(any());
    }

    @Test
    void serviceErrorIsShownWithoutExposingTechnicalDetails() {
        doThrow(new AuthServiceException("Пользователь с таким e-mail уже зарегистрирован"))
                .when(client).register(any());
        ConcurrentModel model = new ConcurrentModel();

        String view = controller.register("Иван", "Иванов", "user@example.com",
                "password1", "password1", "USER", model);

        assertThat(view).isEqualTo("register");
        assertThat(model.getAttribute("registrationError"))
                .isEqualTo("Пользователь с таким e-mail уже зарегистрирован");
    }

    @Test
    void administratorRegistrationWaitsForEmailVerification() {
        String view = controller.register("Анна", "Администратор", "admin@example.com",
                "password1", "password1", "ADMIN", new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/login?adminVerificationPending");
        verify(client).register(new AuthServiceClient.RegistrationCommand(
                "Анна", "Администратор", "admin@example.com", "password1", "password1", "ADMIN"));
    }
}
