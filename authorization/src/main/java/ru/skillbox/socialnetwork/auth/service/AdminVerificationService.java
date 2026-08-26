package ru.skillbox.socialnetwork.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.skillbox.socialnetwork.auth.client.EmailSenderClient;
import ru.skillbox.socialnetwork.auth.dto.request.EmailRequest;
import ru.skillbox.socialnetwork.auth.exception.AdminVerificationRequiredException;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class AdminVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final EmailSenderClient emailSenderClient;

    @Value("${app.frontend.base-url:http://localhost:8080}")
    private String frontendBaseUrl = "http://localhost:8080";

    public void start(UserEntity user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        user.setAdminVerificationTokenHash(hash(token));
        user.setAdminVerificationExpiresAt(Instant.now().plus(TOKEN_LIFETIME));
        user.setAdminEmailVerified(false);
        userRepository.save(user);

        String link = frontendBaseUrl.replaceAll("/+$", "") + "/admin-verification?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String message = """
                Здравствуйте!

                Вы выбрали роль администратора в системе «Научный поиск».
                Чтобы подтвердить действующий адрес электронной почты и активировать права администратора,
                перейдите по ссылке:

                %s

                Ссылка действует 24 часа и может быть использована только один раз.
                Если вы не регистрировались в системе, проигнорируйте это письмо.
                """.formatted(link);
        emailSenderClient.sendEmail(new EmailRequest(
                user.getEmail(), "Подтверждение прав администратора", message, null));
    }

    @Transactional
    public void verify(String token) {
        if (token == null || token.isBlank()) {
            throw new AdminVerificationRequiredException("Ссылка подтверждения отсутствует");
        }
        UserEntity user = userRepository.findByAdminVerificationTokenHash(hash(token.trim()))
                .orElseThrow(() -> new AdminVerificationRequiredException(
                        "Ссылка подтверждения недействительна или уже использована"));
        if (user.getAdminVerificationExpiresAt() == null
                || user.getAdminVerificationExpiresAt().isBefore(Instant.now())) {
            throw new AdminVerificationRequiredException("Срок действия ссылки подтверждения истёк");
        }
        user.setRole("ADMIN");
        user.setAdminEmailVerified(true);
        user.setAdminVerificationTokenHash(null);
        user.setAdminVerificationExpiresAt(null);
        userRepository.save(user);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
