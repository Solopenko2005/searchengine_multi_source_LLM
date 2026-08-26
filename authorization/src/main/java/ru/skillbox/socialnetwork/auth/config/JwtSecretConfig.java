package ru.skillbox.socialnetwork.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;

@Configuration
public class JwtSecretConfig {

    public JwtSecretConfig(@Value("${app.jwt.secret:}") String secret) {
        JwtUtil.configureSecret(secret);
    }
}
