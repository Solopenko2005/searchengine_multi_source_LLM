package searchengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Два режима безопасности:
 * basic — локальный запуск с формой входа;
 * jwt — production-интеграция с внешним OAuth2/OIDC сервисом авторизации.
 */
public final class SecurityConfig {

    private SecurityConfig() {
    }

    private static void authorize(HttpSecurity http) throws Exception {
        http.authorizeRequests(authorize -> authorize
                .antMatchers("/", "/privacy", "/login", "/register", "/forgot-password", "/password-reset", "/admin-verification",
                        "/assets/**", "/error").permitAll()
                .antMatchers(HttpMethod.GET, "/app", "/api/statistics", "/api/search",
                        "/api/assistant/**", "/api/documents/**", "/documents/**",
                        "/api/me", "/api/sources", "/api/groups", "/api/groups/invitations/**",
                        "/api/topics/**", "/api/simple/**", "/api/indexing/status",
                        "/api/indexing/jobs").authenticated()
                .antMatchers(HttpMethod.POST, "/api/assistant/chat", "/api/assistant/chat/**",
                        "/api/assistant/export", "/api/assistant/topics/refresh").authenticated()
                .antMatchers(HttpMethod.POST, "/api/groups/invitations/*/accept").authenticated()
                .antMatchers(HttpMethod.PUT, "/api/assistant/profile").authenticated()
                .antMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().authenticated());
    }

    @Configuration
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "basic", matchIfMissing = true)
    static class BasicSecurity {
        private static final Logger log = LoggerFactory.getLogger(BasicSecurity.class);

        @Bean
        SecurityFilterChain basicFilterChain(HttpSecurity http,
                                             CorsConfigurationSource corsConfigurationSource,
                                             AssistantRateLimitFilter assistantRateLimitFilter,
                                             ObjectProvider<ExternalAuthAuthenticationProvider> externalAuthProvider) throws Exception {
            authorize(http);
            externalAuthProvider.ifAvailable(http::authenticationProvider);
            http.cors().configurationSource(corsConfigurationSource).and()
                    .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                    .formLogin(form -> form
                            .loginPage("/login")
                            .loginProcessingUrl("/login")
                            // Preserve a saved /app?join=... request so an invitation is
                            // accepted by the visitor after authentication.
                            .defaultSuccessUrl("/app", false)
                            .failureUrl("/login?error")
                            .permitAll())
                    .logout(logout -> logout
                            .logoutUrl("/logout")
                            .logoutSuccessUrl("/login?logout")
                            .invalidateHttpSession(true)
                            .clearAuthentication(true)
                            .deleteCookies("JSESSIONID", "XSRF-TOKEN"))
                    .sessionManagement(session -> session
                            .sessionFixation().newSession()
                            .maximumSessions(1))
                    .httpBasic();
            http.addFilterAfter(assistantRateLimitFilter, AnonymousAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }

        @Bean
        @ConditionalOnProperty(name = "app.security.external-auth-enabled", havingValue = "false", matchIfMissing = true)
        UserDetailsService users(PasswordEncoder encoder,
                                 @Value("${app.security.admin-username:admin}") String username,
                                 @Value("${app.security.admin-password:}") String configuredPassword) {
            String password = configuredPassword;
            if (password == null || password.isBlank()) {
                password = UUID.randomUUID().toString();
                log.warn("APP_ADMIN_PASSWORD не задан. Временный пароль пользователя '{}': {}", username, password);
            }
            UserDetails admin = User.withUsername(username)
                    .password(encoder.encode(password))
                    .roles("USER", "ADMIN")
                    .build();
            return new InMemoryUserDetailsManager(admin);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.security.mode", havingValue = "jwt")
    static class JwtSecurity {
        @Value("${app.security.jwt.authorities-claim:roles}")
        private String authoritiesClaim;

        @Value("${app.security.jwt.principal-claim:sub}")
        private String principalClaim;

        @Bean
        SecurityFilterChain jwtFilterChain(HttpSecurity http,
                                           CorsConfigurationSource corsConfigurationSource,
                                           AssistantRateLimitFilter assistantRateLimitFilter) throws Exception {
            authorize(http);
            http.cors().configurationSource(corsConfigurationSource).and()
                    .csrf().disable()
                    .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                    .oauth2ResourceServer(oauth -> oauth
                            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
            http.addFilterAfter(assistantRateLimitFilter, AnonymousAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        JwtDecoder jwtDecoder(@Value("${app.security.jwt.issuer-uri:}") String issuerUri,
                              @Value("${app.security.jwt.jwk-set-uri:}") String jwkSetUri,
                              @Value("${app.security.jwt.audience:}") String audience) {
            JwtDecoder decoder;
            if (jwkSetUri != null && !jwkSetUri.isBlank()) {
                decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            } else if (issuerUri != null && !issuerUri.isBlank()) {
                decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            } else {
                throw new IllegalStateException("JWT mode requires AUTH_ISSUER_URI or AUTH_JWK_SET_URI");
            }

            OAuth2TokenValidator<Jwt> standard = issuerUri == null || issuerUri.isBlank()
                    ? JwtValidators.createDefault()
                    : JwtValidators.createDefaultWithIssuer(issuerUri);
            OAuth2TokenValidator<Jwt> audienceValidator = token -> {
                if (audience == null || audience.isBlank() || token.getAudience().contains(audience)) {
                    return OAuth2TokenValidatorResult.success();
                }
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Required audience is missing", null));
            };
            ((org.springframework.security.oauth2.jwt.NimbusJwtDecoder) decoder)
                    .setJwtValidator(new DelegatingOAuth2TokenValidator<>(standard, audienceValidator));
            return decoder;
        }

        private JwtAuthenticationConverter jwtAuthenticationConverter() {
            JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
            authorities.setAuthoritiesClaimName(authoritiesClaim);
            authorities.setAuthorityPrefix("ROLE_");
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(authorities);
            converter.setPrincipalClaimName(principalClaim);
            return converter;
        }
    }

    @Configuration
    static class SharedSecurityBeans {
        @Bean
        AssistantRateLimitFilter assistantRateLimitFilter(
                @Value("${app.security.assistant-rate-limit-per-minute:30}") int requestsPerMinute) {
            return new AssistantRateLimitFilter(requestsPerMinute);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource(
                @Value("${app.security.allowed-origins:http://localhost:8080}") String origins) {
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList());
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(3600L);
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", configuration);
            return source;
        }
    }
}
