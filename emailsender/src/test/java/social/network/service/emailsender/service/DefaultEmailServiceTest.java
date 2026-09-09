package social.network.service.emailsender.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import social.network.service.emailsender.dto.EmailRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultEmailServiceTest {
    @Test
    void sendsSimpleEmailWithConfiguredSender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        DefaultEmailService service = new DefaultEmailService(sender);
        ReflectionTestUtils.setField(service, "senderAddress", "sender@example.test");

        String result = service.sendSimpleEmail(new EmailRequest("user@example.test", "Тема",
                "Сообщение", null));

        org.mockito.ArgumentCaptor<SimpleMailMessage> message =
                org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("sender@example.test");
        assertThat(message.getValue().getTo()).containsExactly("user@example.test");
        assertThat(message.getValue().getSubject()).isEqualTo("Тема");
        assertThat(result).contains("user@example.test");
    }

    @Test
    void verifiesConnectionForConcreteMailSender() throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        DefaultEmailService service = new DefaultEmailService(sender);

        service.verifyConnection();

        verify(sender).testConnection();
    }

    @Test
    void sendsMimeEmailWithAttachment(@TempDir Path directory) throws Exception {
        Path attachment = Files.writeString(directory.resolve("report.txt"), "report");
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        DefaultEmailService service = new DefaultEmailService(sender);
        ReflectionTestUtils.setField(service, "senderAddress", "sender@example.test");

        String result = service.sendEmailWithInvestment(new EmailRequest("user@example.test",
                "Отчёт", "Файл во вложении", attachment.toString()));

        verify(sender).send(mimeMessage);
        assertThat(mimeMessage.getSubject()).isEqualTo("Отчёт");
        assertThat(result).contains("user@example.test");
    }
}
