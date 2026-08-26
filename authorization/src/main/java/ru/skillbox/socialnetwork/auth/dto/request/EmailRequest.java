package ru.skillbox.socialnetwork.auth.dto.request;

public record EmailRequest(String address,
                           String subject,
                           String message,
                           String investment) {
}
