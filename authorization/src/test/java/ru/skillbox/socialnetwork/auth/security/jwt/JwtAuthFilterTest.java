package ru.skillbox.socialnetwork.auth.security.jwt;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Collections;

import static org.mockito.Mockito.*;

class JwtAuthFilterTest {

    private JwtAuthFilter jwtAuthFilter;
    private UserDetailsService userDetailsService;
    private FilterChain filterChain;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        userDetailsService = mock(UserDetailsService.class);
        jwtAuthFilter = new JwtAuthFilter(userDetailsService);
        filterChain = mock(FilterChain.class);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSkipFilter_whenNoAuthHeader() throws Exception {
        jwtAuthFilter.doFilterInternal(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldSkipFilter_whenHeaderNotBearer() throws Exception {
        request.addHeader("Authorization", "Basic abcdef");
        jwtAuthFilter.doFilterInternal(request, response, filterChain);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldAuthenticate_whenValidToken() throws Exception {
        String token = "valid-token";
        String email = "test@example.com";
        request.addHeader("Authorization", "Bearer " + token);
        UserDetails userDetails = new User(email, "pass", Collections.emptyList());

        try (MockedStatic<JwtUtil> jwtMock = mockStatic(JwtUtil.class)) {
            jwtMock.when(() -> JwtUtil.extractEmail(token)).thenReturn(email);
            jwtMock.when(() -> JwtUtil.validateToken(token)).thenReturn(true);
            when(userDetailsService.loadUserByUsername(email)).thenReturn(userDetails);

            jwtAuthFilter.doFilterInternal(request, response, filterChain);

            verify(userDetailsService).loadUserByUsername(email);
        }
    }

    @Test
    void shouldNotAuthenticate_whenTokenInvalid() throws Exception {
        String token = "invalid-token";
        request.addHeader("Authorization", "Bearer " + token);

        try (MockedStatic<JwtUtil> jwtMock = mockStatic(JwtUtil.class)) {
            jwtMock.when(() -> JwtUtil.extractEmail(token)).thenReturn("user");
            jwtMock.when(() -> JwtUtil.validateToken(token)).thenReturn(false);

            jwtAuthFilter.doFilterInternal(request, response, filterChain);
        }
    }
}
