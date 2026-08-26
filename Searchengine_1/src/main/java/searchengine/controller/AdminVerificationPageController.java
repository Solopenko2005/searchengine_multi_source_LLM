package searchengine.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import searchengine.services.auth.AuthServiceClient;
import searchengine.services.auth.AuthServiceException;

@Controller
@ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "true")
public class AdminVerificationPageController {

    private final AuthServiceClient authServiceClient;

    public AdminVerificationPageController(AuthServiceClient authServiceClient) {
        this.authServiceClient = authServiceClient;
    }

    @GetMapping("/admin-verification")
    public String verify(@RequestParam String token) {
        try {
            authServiceClient.verifyAdministrator(token);
            return "redirect:/login?adminVerified";
        } catch (AuthServiceException exception) {
            return "redirect:/login?adminVerificationError";
        }
    }
}
