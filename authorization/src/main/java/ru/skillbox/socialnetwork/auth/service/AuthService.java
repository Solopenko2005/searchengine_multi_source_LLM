package ru.skillbox.socialnetwork.auth.service;

import org.springframework.security.core.userdetails.UserDetails;
import ru.skillbox.socialnetwork.auth.dto.request.LoginRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RefreshTokenRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.dto.response.AuthResponse;
import ru.skillbox.socialnetwork.auth.dto.response.RefreshTokenResponse;

public interface AuthService {

    void register(RegistrationRequest registrationRequest);

    void verifyAdministrator(String token);

    AuthResponse login(LoginRequest loginRequest);

    RefreshTokenResponse refresh(RefreshTokenRequest refreshTokenRequest);

    void logout(UserDetails user);
}
