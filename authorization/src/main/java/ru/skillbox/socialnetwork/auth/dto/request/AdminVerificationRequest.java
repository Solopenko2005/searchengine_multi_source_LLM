package ru.skillbox.socialnetwork.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminVerificationRequest(@NotBlank String token) {
}
