package ru.skillbox.socialnetwork.auth.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ru.skillbox.socialnetwork.auth.dto.request.EmailRequest;

@FeignClient(name = "email-sender", url = "${email.service.url}")
public interface EmailSenderClient {
    @PostMapping("/api/v1/email/sender")
    String sendEmail(@RequestBody EmailRequest request);
}
