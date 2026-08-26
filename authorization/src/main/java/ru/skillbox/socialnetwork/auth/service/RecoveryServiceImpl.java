package ru.skillbox.socialnetwork.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import ru.skillbox.socialnetwork.auth.client.EmailSenderClient;
import ru.skillbox.socialnetwork.auth.dto.request.ChangeEmailRequest;
import ru.skillbox.socialnetwork.auth.dto.request.ChangePasswordRequest;
import ru.skillbox.socialnetwork.auth.dto.request.EmailRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RecoveryRequest;
import ru.skillbox.socialnetwork.auth.dto.request.PasswordResetRequest;
import ru.skillbox.socialnetwork.auth.exception.EmailNotChangedException;
import ru.skillbox.socialnetwork.auth.exception.PasswordNotMatchesException;
import ru.skillbox.socialnetwork.auth.exception.UserNotFoundException;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;
import ru.skillbox.socialnetwork.auth.persistense.PasswordResetTokenEntity;
import ru.skillbox.socialnetwork.auth.persistense.PasswordResetTokenRepository;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;
import ru.skillbox.socialnetwork.auth.exception.InvalidTokenTypeException;
import ru.skillbox.socialnetwork.auth.exception.TokenExpiredException;
import ru.skillbox.socialnetwork.auth.exception.TokenNotFoundException;

import io.jsonwebtoken.Claims;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecoveryServiceImpl implements RecoveryService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSenderClient emailSenderClient;
    private final PasswordResetTokenRepository resetTokenRepository;

    @Value("${app.frontend.base-url:http://localhost:8080}")
    private String frontendBaseUrl = "http://localhost:8080";

    @Override
    public void recovery(RecoveryRequest recoveryRequest) {
        String email = recoveryRequest.email() == null ? "" : recoveryRequest.email().trim().toLowerCase(Locale.ROOT);
        // Always return the same response to the public recovery form. This prevents
        // attackers from using it to discover which e-mail addresses are registered.
        if (userRepository.findByEmail(email).isEmpty()) return;
        String resetToken = JwtUtil.generateResetToken(email);
        UUID tokenId = JwtUtil.extractTokenId(resetToken);
        PasswordResetTokenEntity tokenEntity = new PasswordResetTokenEntity();
        tokenEntity.setTokenId(tokenId);
        tokenEntity.setEmail(email);
        tokenEntity.setCreatedAt(Instant.now());
        tokenEntity.setExpiresAt(Instant.now().plusMillis(JwtUtil.RESET_TOKEN_EXPIRATION));
        tokenEntity.setUsed(false);
        resetTokenRepository.save(tokenEntity);

        String resetLink = frontendBaseUrl.replaceAll("/+$", "") + "/password-reset?token="
                + URLEncoder.encode(resetToken, StandardCharsets.UTF_8);

        String message = String.format("""
            Здравствуйте!

            Вы запросили восстановление пароля поисковой системы.

            Перейдите по ссылке и задайте новый пароль:
            %s

            Ссылка действует 10 минут и может быть использована только один раз.
            Если вы не отправляли запрос, проигнорируйте это письмо.
            """, resetLink);

        EmailRequest emailRequest = new EmailRequest(
                email,
                "Восстановление пароля поисковой системы",
                message,
                null
        );

        emailSenderClient.sendEmail(emailRequest);
    }

    @Override
    @Transactional
    public void resetPassword(String token, PasswordResetRequest request) {
        if (request == null || request.password() == null || request.password().length() < 8) {
            throw new PasswordNotMatchesException("Password must contain at least 8 characters");
        }
        String confirmation = request.confirmPassword() == null ? request.password() : request.confirmPassword();
        if (!request.password().equals(confirmation)) {
            throw new PasswordNotMatchesException("Passwords do not match");
        }

        Claims claims = JwtUtil.extractAllClaims(token);
        if (!"password_reset".equals(claims.get(JwtUtil.TOKEN_TYPE_CLAIM, String.class))) {
            throw new InvalidTokenTypeException("Invalid password reset token");
        }
        UUID tokenId = JwtUtil.extractTokenId(token);
        PasswordResetTokenEntity tokenEntity = resetTokenRepository.findById(tokenId)
                .orElseThrow(() -> new TokenNotFoundException("Password reset link was not found"));
        if (tokenEntity.isUsed()) throw new TokenNotFoundException("Password reset link has already been used");
        if (tokenEntity.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenExpiredException("Password reset link has expired");
        }
        String email = claims.get(JwtUtil.EMAIL_CLAIM, String.class);
        if (email == null || !email.equalsIgnoreCase(tokenEntity.getEmail())) {
            throw new TokenNotFoundException("Password reset link does not belong to this user");
        }
        UserEntity user = userRepository.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        user.setPassword(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        tokenEntity.setUsed(true);
        resetTokenRepository.save(tokenEntity);
    }


    @Override
    public void changePasswordLink(ChangePasswordRequest changePasswordRequest, String email) {
        UserEntity user = userRepository.findByEmail(email).orElseThrow(() -> new UserNotFoundException("User not found"));
        if (!passwordEncoder.matches(changePasswordRequest.oldPassword(), user.getPassword())) {
            throw new PasswordNotMatchesException("Old password not match");
        }

        if(!changePasswordRequest.newPassword1().equals(changePasswordRequest.newPassword2())) {
            throw new PasswordNotMatchesException("Password not match");
        }

        user.setPassword(passwordEncoder.encode(changePasswordRequest.newPassword1()));
        userRepository.save(user);
    }

    @Override
    public void changeEmailLink(ChangeEmailRequest changeEmailRequest, String oldEmail) {
        UserEntity user = userRepository.findByEmail(oldEmail).orElseThrow(() -> new UserNotFoundException("User not found"));
        String newEmail = changeEmailRequest.email().email();
        if(newEmail.equals(user.getEmail())){
            throw new EmailNotChangedException("New email must be different from the current one");
        }

        user.setEmail(changeEmailRequest.email().email());
        userRepository.save(user);
    }
}
