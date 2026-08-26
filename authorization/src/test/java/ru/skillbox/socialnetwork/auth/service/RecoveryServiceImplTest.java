package ru.skillbox.socialnetwork.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.skillbox.socialnetwork.auth.client.EmailSenderClient;
import ru.skillbox.socialnetwork.auth.dto.request.ChangeEmailRequest;
import ru.skillbox.socialnetwork.auth.dto.request.ChangePasswordRequest;
import ru.skillbox.socialnetwork.auth.dto.request.EmailWrapper;
import ru.skillbox.socialnetwork.auth.dto.request.RecoveryRequest;
import ru.skillbox.socialnetwork.auth.exception.PasswordNotMatchesException;
import ru.skillbox.socialnetwork.auth.exception.UserNotFoundException;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;
import ru.skillbox.socialnetwork.auth.persistense.PasswordResetTokenRepository;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RecoveryServiceImplTest {

    private RecoveryService recoveryService;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private  EmailSenderClient emailSenderClient;
    private PasswordResetTokenRepository resetTokenRepository;

    private final String email = "test@email.com";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailSenderClient = mock(EmailSenderClient.class);
        resetTokenRepository = mock(PasswordResetTokenRepository.class);

        recoveryService = new RecoveryServiceImpl(
                userRepository,
                passwordEncoder,
                emailSenderClient,
                resetTokenRepository);
    }

    @Test
    void recovery_shouldSendEmail_whenUserExists() {
        RecoveryRequest recoveryRequest = new RecoveryRequest(email);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(new UserEntity()));

        try (MockedStatic<JwtUtil> jwtUtilMock = mockStatic(JwtUtil.class)) {
            jwtUtilMock.when(() -> JwtUtil.generateResetToken(email)).thenReturn("123456");
            jwtUtilMock.when(() -> JwtUtil.extractTokenId("123456")).thenReturn(UUID.randomUUID());

            recoveryService.recovery(recoveryRequest);

            verify(emailSenderClient).sendEmail(argThat(emailRequest ->
                    emailRequest.address().equals(email) &&
                            emailRequest.subject().equals("Восстановление пароля поисковой системы") &&
                            emailRequest.message().contains("123456")
            ));
        }
    }

    @Test
    void recovery_shouldNotRevealWhetherUserExists() {
        RecoveryRequest recoveryRequest = new RecoveryRequest(email);
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        recoveryService.recovery(recoveryRequest);

        verify(userRepository).findByEmail(email);
        verifyNoInteractions(emailSenderClient);
    }

    @Test
    void changePasswordLink_shouldChangePassword_whenUserExists() {
        String oldPassword = "oldPassword";
        String newPassword = "newPassword";

        ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest(
                oldPassword, newPassword, newPassword);

        UserEntity user = UserEntity.builder()
                        .password(oldPassword)
                        .email(email)
                        .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(changePasswordRequest.oldPassword(), user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode(newPassword)).thenReturn("encoded");

        recoveryService.changePasswordLink(changePasswordRequest, email);

        assertEquals(email, user.getEmail());
        assertEquals("encoded", user.getPassword());
        verify(userRepository).save(user);
    }

    @Test
    void changePasswordLink_shouldThrowException_whenOldPasswordDoesNotMatch() {
        String oldPassword = "oldPassword";
        String newPassword = "newPassword";

        ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest(oldPassword, newPassword, newPassword);
        UserEntity user = UserEntity.builder()
                .password(oldPassword)
                .email(email)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        when(passwordEncoder.matches(changePasswordRequest.oldPassword(), user.getPassword())).thenReturn(false);

        assertThrows(PasswordNotMatchesException.class, () -> recoveryService.changePasswordLink(changePasswordRequest, email));
    }

    @Test
    void changePasswordLink_shouldThrowException_whenUserNotFound() {
        ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest(
                "old", "new", "new"
        );

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> recoveryService.changePasswordLink(changePasswordRequest, email));

        verify(userRepository).findByEmail(email);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void changePasswordLink_shouldThrowException_whenPasswordsDoNotMatch() {
        ChangePasswordRequest changePasswordRequest = new ChangePasswordRequest(
                "old", "newPassword", "differentConfirm"
        );

        UserEntity user = UserEntity.builder()
                .password("old")
                .email(email)
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        assertThrows(PasswordNotMatchesException.class,
                () -> recoveryService.changePasswordLink(changePasswordRequest, email));
    }

    @Test
    void changeEmailLink_shouldChangeEmail_whenUserExists() {
        ChangeEmailRequest changeEmailRequest = new ChangeEmailRequest(new EmailWrapper("new@email.com"));
        UserEntity user = UserEntity.builder()
                .email(email)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        recoveryService.changeEmailLink(changeEmailRequest, email);

        assertEquals("new@email.com", user.getEmail());
        verify(userRepository).save(user);
    }

    @Test
    void changeEmailLink_shouldThrowException_whenUserDoesNotExist() {
        ChangeEmailRequest changeEmailRequest = new ChangeEmailRequest(new EmailWrapper("new@email.com"));
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> recoveryService.changeEmailLink(changeEmailRequest, email));
    }

}
