package ru.skillbox.socialnetwork.auth.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@UtilityClass
public class JwtUtil {
    public static final long ACCESS_TOKEN_EXPIRATION = 15*60L*1000;
    public static final long REFRESH_TOKEN_EXPIRATION = 7*24*60*60L*1000;
    public static final long RESET_TOKEN_EXPIRATION = 10*60L*1000;
    private static volatile SecretKey secret = generateEphemeralSecret();

    public static final String EMAIL_CLAIM = "email";
    public static final String TOKEN_TYPE_CLAIM = "token_type";
    public static final String TOKEN_ID_CLAIM = "token_id";
    public static final String ACCOUNT_ID_CLAIM = "account_id";
    public static final String ROLES_CLAIM = "roles";

    public static void configureSecret(String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("AUTH_JWT_SECRET is not configured. Tokens will be invalidated after service restart.");
            secret = generateEphemeralSecret();
            return;
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(configuredSecret.getBytes(StandardCharsets.UTF_8));
            secret = Keys.hmacShaKeyFor(key);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String generateAccessToken(String email, UUID accountId) {
        return generateAccessToken(email, accountId, "USER");
    }

    public static String generateAccessToken(String email, UUID accountId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL_CLAIM, email);
        claims.put(ACCOUNT_ID_CLAIM, accountId);
        claims.put(ROLES_CLAIM, List.of(normalizeRole(role)));
        claims.put(TOKEN_TYPE_CLAIM, "access_token");
        claims.put(TOKEN_ID_CLAIM, UUID.randomUUID().toString());

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION))
                .setSubject(email)
                .signWith(secret)
                .compact();
    }

    public static String generateRefreshToken(String email, UUID accountId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL_CLAIM, email);
        claims.put(ACCOUNT_ID_CLAIM, accountId);
        claims.put(ROLES_CLAIM, List.of(normalizeRole(role)));
        claims.put(TOKEN_TYPE_CLAIM, "refresh");
        claims.put(TOKEN_ID_CLAIM, UUID.randomUUID().toString());

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRATION))
                .setSubject(email)
                .signWith(secret)
                .compact();

    }

    public static String generateResetToken(String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(EMAIL_CLAIM, email);
        claims.put(TOKEN_TYPE_CLAIM, "password_reset");
        claims.put(TOKEN_ID_CLAIM, UUID.randomUUID().toString());

        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + RESET_TOKEN_EXPIRATION))
                .setSubject(email)
                .signWith(secret)
                .compact();
    }

    public static boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(secret)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public static String extractEmail(String token) {
        try {
            Claims claims = extractAllClaims(token);

            return claims.get(EMAIL_CLAIM, String.class);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public static Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secret)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public static UUID extractTokenId(String token) {
        String value = extractAllClaims(token).get(TOKEN_ID_CLAIM, String.class);
        return value == null ? null : UUID.fromString(value);
    }

    public static String generateRefreshToken(String email, UUID accountId) {
        return generateRefreshToken(email, accountId, "USER");
    }

    private static String normalizeRole(String role) {
        return role == null || role.isBlank() ? "USER" : role.toUpperCase();
    }

    private static SecretKey generateEphemeralSecret() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Keys.hmacShaKeyFor(key);
    }

}
