package ru.skillbox.socialnetwork.auth.exception;

public class AdminVerificationRequiredException extends RuntimeException {
    public AdminVerificationRequiredException(String message) {
        super(message);
    }
}
