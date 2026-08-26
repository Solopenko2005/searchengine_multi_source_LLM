package ru.skillbox.socialnetwork.auth.dto.request;

public record ChangePasswordRequest (String oldPassword,
                                     String newPassword1,
                                     String newPassword2) {
}
