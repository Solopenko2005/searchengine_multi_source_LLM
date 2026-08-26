package ru.skillbox.socialnetwork.auth.service;

import ru.skillbox.socialnetwork.auth.dto.request.ChangeEmailRequest;
import ru.skillbox.socialnetwork.auth.dto.request.ChangePasswordRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RecoveryRequest;
import ru.skillbox.socialnetwork.auth.dto.request.PasswordResetRequest;

public interface RecoveryService {
    void recovery(RecoveryRequest recoveryRequest);
    void resetPassword(String token, PasswordResetRequest request);
    void changePasswordLink(ChangePasswordRequest changePasswordRequest, String email);
    void changeEmailLink(ChangeEmailRequest changeEmailRequest, String oldEmail);
}
