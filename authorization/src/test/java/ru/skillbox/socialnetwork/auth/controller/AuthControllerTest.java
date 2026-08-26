package ru.skillbox.socialnetwork.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.skillbox.socialnetwork.auth.dto.request.LoginRequest;
import ru.skillbox.socialnetwork.auth.dto.request.AdminVerificationRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RecoveryRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RefreshTokenRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.dto.response.AuthResponse;
import ru.skillbox.socialnetwork.auth.dto.response.CaptchaResponse;
import ru.skillbox.socialnetwork.auth.dto.response.RefreshTokenResponse;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;
import ru.skillbox.socialnetwork.auth.service.AuthService;
import ru.skillbox.socialnetwork.auth.service.CaptchaService;
import ru.skillbox.socialnetwork.auth.service.RecoveryService;

import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @InjectMocks
    private AuthController authController;

    @Mock
    private AuthService authService;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private RecoveryService recoveryService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String email = "test@email.com";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void login_shouldReturnAuthResponse_whenCredentialsValid() throws Exception {
        LoginRequest loginRequest = new LoginRequest(email, "password");
        AuthResponse expectedResponse = new AuthResponse("access-token", "refresh-token", email, Set.of("ADMIN"));

        when(authService.login(loginRequest)).thenReturn(expectedResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    void register_shouldCallService_whenValidRequest() throws Exception {
        RegistrationRequest registrationRequest = new RegistrationRequest(
                "first", "last", email, "password1", "password1", "captcha", "USER");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationRequest)))
                .andExpect(status().isOk());

        verify(authService).register(registrationRequest);
    }

    @Test
    void verifyAdministrator_shouldCallService() throws Exception {
        AdminVerificationRequest request = new AdminVerificationRequest("verification-token");

        mockMvc.perform(post("/api/v1/auth/admin/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(authService).verifyAdministrator("verification-token");
    }

    @Test
    void refresh_shouldReturnNewTokens() throws Exception {
        RefreshTokenRequest refreshTokenRequest = new RefreshTokenRequest("old-refresh-token");
        RefreshTokenResponse response = new RefreshTokenResponse("new-access", "new-refresh");

        when(authService.refresh(refreshTokenRequest)).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshTokenRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"));
    }

    @Test
    void captcha_shouldReturnCaptchaResponse() throws Exception {
        CaptchaResponse response = new CaptchaResponse("ABC123", "img-data");
        when(captchaService.generateCaptcha()).thenReturn(response);

        mockMvc.perform(get("/api/v1/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("ABC123"))
                .andExpect(jsonPath("$.image").value("img-data"));
    }

    @Test
    void validate_shouldReturnFalse_whenTokenInvalid() throws Exception {
        try (MockedStatic<JwtUtil> mockedJwtUtil = mockStatic(JwtUtil.class)) {
            mockedJwtUtil.when(() -> JwtUtil.validateToken("invalid-token")).thenReturn(false);

            mockMvc.perform(get("/api/v1/auth/validate")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("false"));
        }
    }

    @Test
    void recovery_shouldCallService() throws Exception {
        RecoveryRequest request = new RecoveryRequest(email);

        mockMvc.perform(post("/api/v1/auth/password/recovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(recoveryService).recovery(request);
    }
}
