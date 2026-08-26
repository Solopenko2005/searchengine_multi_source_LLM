package ru.skillbox.socialnetwork.auth.service;

import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;

public interface ValidationService {
    void validateConfirmPassword(RegistrationRequest registrationRequest);
}
