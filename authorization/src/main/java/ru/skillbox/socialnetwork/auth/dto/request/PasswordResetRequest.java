package ru.skillbox.socialnetwork.auth.dto.request;

public record PasswordResetRequest(String password, String confirmPassword) {
}
