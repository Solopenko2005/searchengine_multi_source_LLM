package ru.skillbox.socialnetwork.auth.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private final String email = "test@example.com";
    private final UUID accountId = UUID.randomUUID();

    @Test
    void generateAccessToken_shouldContainCorrectClaims() {
        String token = JwtUtil.generateAccessToken(email, accountId, "ADMIN");
        Claims claims = JwtUtil.extractAllClaims(token);

        assertEquals(email, claims.get(JwtUtil.EMAIL_CLAIM, String.class));
        assertEquals(accountId.toString(), claims.get(JwtUtil.ACCOUNT_ID_CLAIM, String.class));
        assertEquals("access_token", claims.get(JwtUtil.TOKEN_TYPE_CLAIM));
        assertEquals("ADMIN", claims.get(JwtUtil.ROLES_CLAIM, java.util.List.class).get(0));
        assertNotNull(claims.get(JwtUtil.TOKEN_ID_CLAIM));
    }

    @Test
    void generateRefreshToken_shouldContainCorrectClaims() {
        String token = JwtUtil.generateRefreshToken(email, accountId);
        Claims claims = JwtUtil.extractAllClaims(token);

        assertEquals(email, claims.get(JwtUtil.EMAIL_CLAIM, String.class));
        assertEquals(accountId.toString(), claims.get(JwtUtil.ACCOUNT_ID_CLAIM, String.class));
        assertEquals("refresh", claims.get(JwtUtil.TOKEN_TYPE_CLAIM));
        assertNotNull(claims.get(JwtUtil.TOKEN_ID_CLAIM));
    }

    @Test
    void generateResetToken_shouldContainPasswordResetClaims() {
        String token = JwtUtil.generateResetToken(email);
        Claims claims = JwtUtil.extractAllClaims(token);

        assertEquals(email, claims.get(JwtUtil.EMAIL_CLAIM, String.class));
        assertNull(claims.get(JwtUtil.ACCOUNT_ID_CLAIM));
        assertEquals("password_reset", claims.get(JwtUtil.TOKEN_TYPE_CLAIM));
        assertNotNull(claims.get(JwtUtil.TOKEN_ID_CLAIM));
    }

    @Test
    void validateToken_shouldReturnTrueForValidToken() {
        String token = JwtUtil.generateAccessToken(email, accountId);
        assertTrue(JwtUtil.validateToken(token));
    }

    @Test
    void validateToken_shouldReturnFalseForInvalidToken() {
        assertFalse(JwtUtil.validateToken("invalid.token.value"));
    }

    @Test
    void extractEmail_shouldReturnEmailFromValidToken() {
        String token = JwtUtil.generateAccessToken(email, accountId);
        String extractedEmail = JwtUtil.extractEmail(token);
        assertEquals(email, extractedEmail);
    }

    @Test
    void extractEmail_shouldReturnNullForInvalidToken() {
        assertNull(JwtUtil.extractEmail("invalid.token"));
    }

    @Test
    void extractAllClaims_shouldThrowExceptionForInvalidToken() {
        assertThrows(JwtException.class, () -> JwtUtil.extractAllClaims("broken.token.value"));
    }
}
