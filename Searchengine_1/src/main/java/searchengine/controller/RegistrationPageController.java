package searchengine.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import searchengine.services.auth.AuthServiceClient;
import searchengine.services.auth.AuthServiceException;

@Controller
@ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "true")
public class RegistrationPageController {

    private final AuthServiceClient authServiceClient;

    public RegistrationPageController(AuthServiceClient authServiceClient) {
        this.authServiceClient = authServiceClient;
    }

    @GetMapping("/register")
    public String page(Authentication authentication) {
        if (isAuthenticated(authentication)) {
            return "redirect:/app";
        }
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String firstName,
                           @RequestParam String lastName,
                           @RequestParam String email,
                           @RequestParam String password,
                           @RequestParam String confirmPassword,
                           @RequestParam(defaultValue = "USER") String role,
                           Model model) {
        model.addAttribute("firstName", firstName);
        model.addAttribute("lastName", lastName);
        model.addAttribute("email", email);
        model.addAttribute("role", role);

        if (firstName.isBlank() || lastName.isBlank() || email.isBlank()) {
            model.addAttribute("registrationError", "Заполните имя, фамилию и e-mail");
            return "register";
        }
        if (password.length() < 8) {
            model.addAttribute("registrationError", "Пароль должен содержать не менее 8 символов");
            return "register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("registrationError", "Пароли не совпадают");
            return "register";
        }
        String normalizedRole = role == null ? "USER" : role.trim().toUpperCase();
        if (!normalizedRole.equals("USER") && !normalizedRole.equals("ADMIN")) {
            model.addAttribute("registrationError", "Выберите доступ «Администратор» или «Посетитель»");
            return "register";
        }

        try {
            authServiceClient.register(new AuthServiceClient.RegistrationCommand(
                    firstName.trim(), lastName.trim(), email.trim().toLowerCase(), password,
                    confirmPassword, normalizedRole));
            return normalizedRole.equals("ADMIN")
                    ? "redirect:/login?adminVerificationPending"
                    : "redirect:/login?registered";
        } catch (AuthServiceException exception) {
            model.addAttribute("registrationError", exception.getMessage());
            return "register";
        }
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
