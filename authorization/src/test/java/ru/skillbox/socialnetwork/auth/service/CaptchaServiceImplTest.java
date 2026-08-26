package ru.skillbox.socialnetwork.auth.service;

import org.junit.jupiter.api.Test;
import ru.skillbox.socialnetwork.auth.dto.response.CaptchaResponse;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaServiceImplTest {
    private final CaptchaService captchaService = new CaptchaServiceImpl();

    @Test
    void generateCaptcha_shouldReturnValidCaptchaResponse() {
        CaptchaResponse response = captchaService.generateCaptcha();

        assertNotNull(response);
        assertNotNull(response.secret());
        assertNotNull(response.image());
        assertFalse(response.secret().isEmpty());
        assertTrue(response.image().startsWith("data:image/png;base64,"));
    }
}
