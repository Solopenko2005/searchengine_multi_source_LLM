package social.network.service.emailsender.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record EmailRequest(
        @NotBlank
        @Email
        String address,
        @NotBlank
        String subject,
        @NotBlank
        String message,
        String investment
) {
}
