package ru.skillbox.socialnetwork.auth.service;

import org.junit.jupiter.api.Test;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.exception.PasswordNotMatchesException;

import static org.junit.jupiter.api.Assertions.*;

class ValidationServiceImplTest {

    private final ValidationServiceImpl validationService = new ValidationServiceImpl();

    @Test
    void validateConfirmPassword_shouldPass_whenPasswordsMatch() {
        RegistrationRequest request = RegistrationRequest.builder()
                .email("test@email.com")
                .password1("password123")
                .password2("password123")
                .build();

        assertDoesNotThrow(() -> validationService.validateConfirmPassword(request));
    }

    @Test
    void validateConfirmPassword_shouldThrowException_whenPasswordsDoNotMatch() {
        RegistrationRequest request = RegistrationRequest.builder()
                .email("test@email.com")
                .password1("password123")
                .password2("wrongPassword")
                .build();

        PasswordNotMatchesException exception = assertThrows(
                PasswordNotMatchesException.class,
                () -> validationService.validateConfirmPassword(request)
        );

        assertEquals("Confirm password does not match", exception.getMessage());
    }
}
