package social.network.service.emailsender.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import social.network.service.emailsender.dto.EmailRequest;
import social.network.service.emailsender.service.DefaultEmailService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/email")
public class EmailSenderController {
    private final DefaultEmailService emailService;

    public EmailSenderController(DefaultEmailService emailService) {
        this.emailService = emailService;
    }

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/sender")
    public String sendEmail(@Valid @RequestBody EmailRequest request){
        return emailService.sendSimpleEmail(request);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            emailService.verifyConnection();
            return ResponseEntity.ok(Map.of("result", true, "smtp", "ready"));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("result", false, "smtp", "unavailable",
                            "error", "SMTP authentication failed. Check the sender address and app password."));
        }
    }
}
