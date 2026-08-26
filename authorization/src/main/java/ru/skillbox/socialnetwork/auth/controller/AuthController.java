package ru.skillbox.socialnetwork.auth.controller;

import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import ru.skillbox.socialnetwork.auth.dto.request.*;
import ru.skillbox.socialnetwork.auth.dto.response.AuthResponse;
import ru.skillbox.socialnetwork.auth.dto.response.CaptchaResponse;
import ru.skillbox.socialnetwork.auth.dto.response.RefreshTokenResponse;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;
import ru.skillbox.socialnetwork.auth.service.AuthService;
import ru.skillbox.socialnetwork.auth.service.CaptchaService;
import ru.skillbox.socialnetwork.auth.service.RecoveryService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CaptchaService captchaService;
    private final RecoveryService recoveryService;

    @PostMapping("/register")
    public void register(@Valid @RequestBody RegistrationRequest registrationRequest) {
        authService.register(registrationRequest);
    }

    @PostMapping("/admin/verify")
    public void verifyAdministrator(@Valid @RequestBody AdminVerificationRequest request) {
        authService.verifyAdministrator(request.token());
    }

    @PostMapping("/refresh")
    public RefreshTokenResponse refresh(@RequestBody RefreshTokenRequest refreshTokenRequest) {
        return authService.refresh(refreshTokenRequest);
    }

    @PostMapping("/password/recovery")
    public void recovery(@RequestBody RecoveryRequest recoveryRequest) {
        recoveryService.recovery(recoveryRequest);
    }

    @PostMapping("/password/recovery/{token}")
    public void resetPassword(@PathVariable String token,
                              @RequestBody PasswordResetRequest request) {
        recoveryService.resetPassword(token, request);
    }

    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal UserDetails user) {
        authService.logout(user);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest loginRequest) {
        return authService.login(loginRequest);
    }

    @PostMapping("/change-password-link")
    public void changePasswordLink(@AuthenticationPrincipal UserDetails user,
                                   @RequestBody ChangePasswordRequest changePasswordRequest) {
        recoveryService.changePasswordLink(changePasswordRequest, user.getUsername());
    }

    @PostMapping("/change-email-link")
    public void changeEmailLink(@AuthenticationPrincipal UserDetails user,
            @RequestBody ChangeEmailRequest changeEmailRequest) {
        recoveryService.changeEmailLink(changeEmailRequest, user.getUsername());
    }

    @GetMapping("/validate")
    public Boolean validate(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }

        String token = authHeader.substring(7);
        return JwtUtil.validateToken(token);
    }

    @GetMapping("/captcha")
    public CaptchaResponse captcha() {
        return captchaService.generateCaptcha();
    }

}
