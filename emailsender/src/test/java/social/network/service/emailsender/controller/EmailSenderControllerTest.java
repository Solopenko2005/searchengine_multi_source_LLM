package social.network.service.emailsender.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import social.network.service.emailsender.dto.EmailRequest;
import social.network.service.emailsender.service.DefaultEmailService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailSenderControllerTest {
    @Test
    void delegatesSendingAndReportsHealthySmtp() throws Exception {
        DefaultEmailService service = mock(DefaultEmailService.class);
        EmailRequest request = new EmailRequest("user@example.test", "Тема", "Текст", null);
        when(service.sendSimpleEmail(request)).thenReturn("sent");
        EmailSenderController controller = new EmailSenderController(service);

        assertThat(controller.sendEmail(request)).isEqualTo("sent");
        assertThat(controller.health().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.health().getBody()).containsEntry("smtp", "ready");
        verify(service).sendSimpleEmail(request);
    }

    @Test
    void reportsUnavailableSmtpWithoutLeakingProviderError() throws Exception {
        DefaultEmailService service = mock(DefaultEmailService.class);
        doThrow(new IllegalStateException("secret provider detail")).when(service).verifyConnection();
        EmailSenderController controller = new EmailSenderController(service);

        var response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("smtp", "unavailable");
        assertThat(response.getBody().get("error").toString()).doesNotContain("secret provider detail");
    }
}
