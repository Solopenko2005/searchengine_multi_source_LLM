package ru.skillbox.socialnetwork.auth.service;

import ru.skillbox.socialnetwork.auth.dto.response.CaptchaResponse;

public interface CaptchaService {
    CaptchaResponse generateCaptcha();
}
