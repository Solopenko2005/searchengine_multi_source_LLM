package ru.skillbox.socialnetwork.auth.exception;

public class PasswordNotMatchesException extends RuntimeException {
    public PasswordNotMatchesException(String message) {
        super(message);
    }
}
