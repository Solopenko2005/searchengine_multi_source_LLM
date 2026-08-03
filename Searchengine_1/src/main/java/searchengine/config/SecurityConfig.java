package searchengine.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
                .antMatchers("/login", "/assets/**", "/error").permitAll()
                .antMatchers(HttpMethod.GET, "/", "/api/statistics", "/api/search",
                        "/api/assistant/**", "/api/documents/**", "/documents/**",
                        "/api/indexing/status").authenticated()
                .antMatchers(HttpMethod.POST, "/api/assistant/chat").authenticated()
                .antMatchers(HttpMethod.POST, "/api/uploadDocument").authenticated()
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
                                             AssistantRateLimitFilter assistantRateLimitFilter) throws Exception {
            authorize(http);
            http.cors().configurationSource(corsConfigurationSource).and()
                    .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                    .formLogin().and()
                    .httpBasic();
            http.addFilterAfter(assistantRateLimitFilter, AnonymousAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }

        @Bean
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

        private JwtAuthenticationConverter jwtAuthenticationConverter() {
            JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
            authorities.setAuthoritiesClaimName("roles");
            authorities.setAuthorityPrefix("ROLE_");
            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(authorities);
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
