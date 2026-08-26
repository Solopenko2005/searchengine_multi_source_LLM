package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import searchengine.services.auth.AuthServiceClient;
import searchengine.services.auth.AuthServiceException;

@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "true")
public class PasswordRecoveryPageController {
    private final AuthServiceClient authServiceClient;

    @GetMapping("/forgot-password")
    public String forgotPage() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String request(@RequestParam String email, Model model) {
        model.addAttribute("email", email);
        try {
            authServiceClient.requestPasswordRecovery(email.trim().toLowerCase());
            model.addAttribute("sent", true);
        } catch (AuthServiceException exception) {
            model.addAttribute("recoveryError", exception.getMessage());
        }
        return "forgot-password";
    }

    @GetMapping("/password-reset")
    public String resetPage(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "password-reset";
    }

    @PostMapping("/password-reset")
    public String reset(@RequestParam String token,
                        @RequestParam String password,
                        @RequestParam String confirmPassword,
                        Model model) {
        model.addAttribute("token", token);
        if (password.length() < 8) {
            model.addAttribute("recoveryError", "Пароль должен содержать не менее 8 символов");
            return "password-reset";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("recoveryError", "Пароли не совпадают");
            return "password-reset";
        }
        try {
            authServiceClient.resetPassword(token, password, confirmPassword);
            return "redirect:/login?passwordReset";
        } catch (AuthServiceException exception) {
            model.addAttribute("recoveryError", exception.getMessage());
            return "password-reset";
        }
    }
}
