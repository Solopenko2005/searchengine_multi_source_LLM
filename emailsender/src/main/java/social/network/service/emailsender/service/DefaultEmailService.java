package social.network.service.emailsender.service;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import social.network.service.emailsender.dto.EmailRequest;

import java.io.File;

@Service
public class DefaultEmailService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEmailService.class);

    public final JavaMailSender emailSender;

    @Value("${spring.mail.username:}")
    private String senderAddress;

    public DefaultEmailService(JavaMailSender emailSender) {
        this.emailSender = emailSender;
    }

    public String sendSimpleEmail(EmailRequest request){
        log.info("Сообщение получено и обрабатывается");
        SimpleMailMessage simpleMailMessage = new SimpleMailMessage();
        if (senderAddress != null && !senderAddress.isBlank()) {
            simpleMailMessage.setFrom(senderAddress);
        }
        simpleMailMessage.setTo(request.address());
        simpleMailMessage.setSubject(request.subject());
        simpleMailMessage.setText(request.message());
        emailSender.send(simpleMailMessage);
        log.info("Сообщение обработано и отправлено - address {}",request.address());
        return String.format("Email sent successfully to %s",request.address());
    }

    public void verifyConnection() throws MessagingException {
        if (emailSender instanceof JavaMailSenderImpl sender) {
            sender.testConnection();
        }
    }

    public String sendEmailWithInvestment(EmailRequest request) throws MessagingException{
        MimeMessage mimeMessage = emailSender.createMimeMessage();
        MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage,true);
        if (senderAddress != null && !senderAddress.isBlank()) {
            messageHelper.setFrom(senderAddress);
        }
        messageHelper.setTo(request.address());
        messageHelper.setSubject(request.subject());
        messageHelper.setText(request.message());
        FileSystemResource file = new FileSystemResource(new File(request.investment()));
        messageHelper.addAttachment("Purchase Order",file);
        emailSender.send(mimeMessage);
        return String.format("Email sent successfully to %s",request.address());
    }
}
