package ru.skillbox.socialnetwork.auth.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.skillbox.socialnetwork.auth.dto.RefreshTokenDto;
import ru.skillbox.socialnetwork.auth.dto.request.LoginRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RefreshTokenRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.dto.response.AuthResponse;
import ru.skillbox.socialnetwork.auth.dto.response.RefreshTokenResponse;
import ru.skillbox.socialnetwork.auth.exception.*;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceImplTest {
    private AuthService authService;
    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private ValidationService validationService;
    private KafkaService kafkaService;
    private Map<String, RefreshTokenDto> refreshTokens;

    private final String email = "test@email.com";
    private final String password = "test";
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setup(){
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        validationService = mock(ValidationService.class);
        kafkaService = mock(KafkaService.class);
        refreshTokens = new HashMap<>();

        authService = new AuthServiceImpl(
                userRepository,
                validationService,
                kafkaService,
                passwordEncoder,
                false,
                "ADMIN",
                refreshTokens
        );
    }


    @Test
    void register_shouldBeSuccessful_whenUserNotExist() throws Exception{
        RegistrationRequest registrationRequest = RegistrationRequest.builder()
                .firstName("User")
                .lastName("Test")
                .email(email)
                .password1(password)
                .password2(password)
                .captchaCode("code")
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(userId);
            }
            return user;
        });

        authService.register(registrationRequest);

        verify(userRepository, times(2)).save(any(UserEntity.class));
        verify(userRepository, times(1)).findByEmail(email);
        verify(passwordEncoder, times(1)).encode(password);
        verifyNoInteractions(kafkaService);
    }

    @Test
    void register_shouldThrowException_whenUserAlreadyExists() {
        RegistrationRequest registrationRequest = RegistrationRequest.builder()
                .firstName("User")
                .lastName("Test")
                .email(email)
                .password1(password)
                .password2(password)
                .captchaCode("code")
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(createUser()));

        assertThrows(UserAlreadyExistsException.class, () -> authService.register(registrationRequest));

        verify(userRepository).findByEmail(email);
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(kafkaService);
    }

    @Test
    void bootstrapEmailGetsAdminRoleWhileDefaultRoleIsUser() throws Exception {
        authService = new AuthServiceImpl(userRepository, validationService, kafkaService,
                passwordEncoder, false, "USER", Set.of(email), refreshTokens);
        RegistrationRequest request = RegistrationRequest.builder()
                .firstName("User").lastName("Test").email(email)
                .password1(password).password2(password).captchaCode("code").build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(userId);
            return user;
        });

        authService.register(request);

        verify(userRepository, atLeastOnce()).save(argThat(user -> "ADMIN".equals(user.getRole())));
    }

    @Test
    void explicitlyRequestedAdminWaitsForEmailVerification() throws Exception {
        AdminVerificationService adminVerificationService = mock(AdminVerificationService.class);
        authService = new AuthServiceImpl(userRepository, validationService, kafkaService,
                passwordEncoder, false, "USER", Set.of(), refreshTokens, adminVerificationService);
        RegistrationRequest request = RegistrationRequest.builder()
                .firstName("User").lastName("Test").email(email)
                .password1(password).password2(password).captchaCode("code")
                .role("ADMIN").build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(password)).thenReturn("encoded");
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(userId);
            return user;
        });

        authService.register(request);

        verify(adminVerificationService).start(argThat(user ->
                "PENDING_ADMIN".equals(user.getRole()) && !user.isAdminEmailVerified()));
    }


    @Test
    void login_shouldReturnTokens_whenUserExists() {
        UserEntity user = createUser();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, "encoded" )).thenReturn(true);

        LoginRequest loginRequest = new LoginRequest(email, password);
        AuthResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertEquals(Set.of("ADMIN"), response.getRoles());

        verify(userRepository).findByEmail(email);
        verify(passwordEncoder).matches(password, "encoded");
    }

    @Test
    void login_shouldThrowException_whenPasswordDoesNotMatch() {
        UserEntity user = createUser();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(password, "encoded")).thenReturn(false);

        LoginRequest loginRequest = new LoginRequest(email, password);

        assertThrows(PasswordNotMatchesException.class, () -> authService.login(loginRequest));

        verify(userRepository).findByEmail(email);
        verify(passwordEncoder).matches(password, "encoded");
    }


    @Test
    void login_shouldThrowException_whenUserDoesNotExist() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        LoginRequest loginRequest = new LoginRequest(email, password);

        assertThrows(UserNotFoundException.class, () -> authService.login(loginRequest));
        verify(userRepository).findByEmail(email);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void refresh_shouldReturnTokens_whenRefreshTokenExists() {
        String oldRefreshToken = "valid-refresh-token";

        RefreshTokenRequest request = new RefreshTokenRequest(oldRefreshToken);

        UserEntity user = createUser();
        Claims claims = mock(Claims.class);
        when(claims.get(JwtUtil.TOKEN_TYPE_CLAIM)).thenReturn("refresh");
        when(claims.get(JwtUtil.EMAIL_CLAIM, String.class)).thenReturn(email);
        try (MockedStatic<JwtUtil> jwtUtilMock = mockStatic(JwtUtil.class)) {
            jwtUtilMock.when(() -> JwtUtil.extractAllClaims(oldRefreshToken))
                    .thenReturn(claims);
            jwtUtilMock.when(() -> JwtUtil.generateAccessToken(email, accountId, "ADMIN"))
                    .thenReturn("new-access-token");
            jwtUtilMock.when(() -> JwtUtil.generateRefreshToken(email, accountId, "ADMIN"))
                    .thenReturn("new-refresh-token");

            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

            RefreshTokenDto savedToken = RefreshTokenDto.builder()
                    .token(oldRefreshToken)
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
            String redisKey = "refresh:" + user.getId();
            refreshTokens.put(redisKey, savedToken);

            RefreshTokenResponse response = authService.refresh(request);

            assertNotNull(response);
            assertEquals("new-access-token", response.getAccessToken());
            assertEquals("new-refresh-token", response.getRefreshToken());
        }
    }

    @Test
    void refresh_shouldThrowException_whenTokenExpired() {
        String oldRefreshToken = "expired-token";
        RefreshTokenRequest request = new RefreshTokenRequest(oldRefreshToken);
        UserEntity user = createUser();

        Claims claims = mock(Claims.class);
        when(claims.get(JwtUtil.TOKEN_TYPE_CLAIM)).thenReturn("refresh");
        when(claims.get(JwtUtil.EMAIL_CLAIM, String.class)).thenReturn(email);

        try (MockedStatic<JwtUtil> jwtUtilMock = mockStatic(JwtUtil.class)) {
            jwtUtilMock.when(() -> JwtUtil.extractAllClaims(oldRefreshToken)).thenReturn(claims);
            when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

            RefreshTokenDto expiredToken = RefreshTokenDto.builder()
                    .token(oldRefreshToken)
                    .expiresAt(Instant.now().minusSeconds(60)) // просрочен
                    .build();

            String redisKey = "refresh:" + user.getId();
            refreshTokens.put(redisKey, expiredToken);

            assertThrows(TokenNotFoundException.class, () -> authService.refresh(request));
        }
    }

    @Test
    void refresh_shouldThrowException_whenWrongTokenType() {
        String token = "access-token";
        RefreshTokenRequest request = new RefreshTokenRequest(token);

        Claims claims = mock(Claims.class);
        when(claims.get(JwtUtil.TOKEN_TYPE_CLAIM)).thenReturn("access");

        try (MockedStatic<JwtUtil> jwtUtilMock = mockStatic(JwtUtil.class)) {
            jwtUtilMock.when(() -> JwtUtil.extractAllClaims(token)).thenReturn(claims);

            assertThrows(InvalidTokenTypeException.class, () -> authService.refresh(request));
        }
    }


    @Test
    void logout_shouldLogout_whenUserExist() {
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn(email);
        when(userRepository.getUserIdByEmail(email)).thenReturn(userId);
        refreshTokens.put("refresh:" + userId, RefreshTokenDto.builder()
                .token("token")
                .expiresAt(Instant.now().plusSeconds(60))
                .build());

        authService.logout(userDetails);
        verify(userRepository, times(1)).getUserIdByEmail(email);
        assertFalse(refreshTokens.containsKey("refresh:" + userId));

    }

    private UserEntity createUser() {
        return UserEntity.builder()
                .id(userId)
                .accountId(accountId)
                .email(email)
                .password("encoded")
                .role("ADMIN")
                .adminEmailVerified(true)
                .build();
    }
}
