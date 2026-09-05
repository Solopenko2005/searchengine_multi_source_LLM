package ru.skillbox.socialnetwork.auth.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.skillbox.socialnetwork.auth.client.EmailSenderClient;
import ru.skillbox.socialnetwork.auth.dto.request.EmailRequest;

@Service
@RequiredArgsConstructor
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    private final EmailSenderClient emailSenderClient;

    @Async("emailTaskExecutor")
    public void send(EmailRequest request) {
        try {
            emailSenderClient.sendEmail(request);
        } catch (RuntimeException exception) {
            log.error("Не удалось отправить письмо с темой '{}': {}",
                    request.subject(), exception.getMessage());
        }
    }
}
