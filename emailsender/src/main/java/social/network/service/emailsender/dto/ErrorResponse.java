package social.network.service.emailsender.dto;

public record ErrorResponse(
        int code,
        String message
) {
}
