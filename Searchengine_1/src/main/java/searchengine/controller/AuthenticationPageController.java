package searchengine.controller;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the local Spring Security sign-in page. */
@Controller
public class AuthenticationPageController {

    private final boolean registrationEnabled;

    public AuthenticationPageController(
            @Value("${app.security.external-auth-enabled:false}") boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping("/login")
    public String login(Authentication authentication, Model model) {
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/";
        }
        model.addAttribute("registrationEnabled", registrationEnabled);
        return "login";
    }
}
