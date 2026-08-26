package ru.skillbox.socialnetwork.auth.service;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.skillbox.socialnetwork.auth.dto.RefreshTokenDto;
import ru.skillbox.socialnetwork.auth.dto.kafka.DetailedRegistrationEvent;
import ru.skillbox.socialnetwork.auth.dto.request.RefreshTokenRequest;
import ru.skillbox.socialnetwork.auth.dto.response.RefreshTokenResponse;
import ru.skillbox.socialnetwork.auth.exception.*;
import ru.skillbox.socialnetwork.auth.security.jwt.JwtUtil;
import ru.skillbox.socialnetwork.auth.dto.request.LoginRequest;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.dto.response.AuthResponse;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;
import io.jsonwebtoken.Claims;


import java.time.Instant;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final ValidationService validationService;
    private final KafkaService kafkaService;
    private final PasswordEncoder passwordEncoder;
    private final boolean accountServiceEnabled;
    private final String defaultRole;
    private final Set<String> bootstrapAdminEmails;
    private final Map<String, RefreshTokenDto> refreshTokens;
    private final AdminVerificationService adminVerificationService;

    @Autowired
    public AuthServiceImpl(UserRepository userRepository,
                           ValidationService validationService,
                           KafkaService kafkaService,
                           PasswordEncoder passwordEncoder,
                           AdminVerificationService adminVerificationService,
                           @Value("${app.integrations.account-service-enabled:false}") boolean accountServiceEnabled,
                           @Value("${app.registration.default-role:USER}") String defaultRole,
                           @Value("${app.registration.bootstrap-admin-emails:}") String bootstrapAdminEmails) {
        this(userRepository, validationService, kafkaService, passwordEncoder,
                accountServiceEnabled, defaultRole, parseEmails(bootstrapAdminEmails), new ConcurrentHashMap<>(),
                adminVerificationService);
    }

    AuthServiceImpl(UserRepository userRepository,
                    ValidationService validationService,
                    KafkaService kafkaService,
                    PasswordEncoder passwordEncoder,
                    boolean accountServiceEnabled,
                    String defaultRole,
                    Map<String, RefreshTokenDto> refreshTokens) {
        this(userRepository, validationService, kafkaService, passwordEncoder, accountServiceEnabled,
                defaultRole, Set.of(), refreshTokens, null);
    }

    AuthServiceImpl(UserRepository userRepository,
                    ValidationService validationService,
                    KafkaService kafkaService,
                    PasswordEncoder passwordEncoder,
                    boolean accountServiceEnabled,
                    String defaultRole,
                    Set<String> bootstrapAdminEmails,
                    Map<String, RefreshTokenDto> refreshTokens) {
        this(userRepository, validationService, kafkaService, passwordEncoder, accountServiceEnabled,
                defaultRole, bootstrapAdminEmails, refreshTokens, null);
    }

    AuthServiceImpl(UserRepository userRepository,
                    ValidationService validationService,
                    KafkaService kafkaService,
                    PasswordEncoder passwordEncoder,
                    boolean accountServiceEnabled,
                    String defaultRole,
                    Set<String> bootstrapAdminEmails,
                    Map<String, RefreshTokenDto> refreshTokens,
                    AdminVerificationService adminVerificationService) {
        this.userRepository = userRepository;
        this.validationService = validationService;
        this.kafkaService = kafkaService;
        this.passwordEncoder = passwordEncoder;
        this.accountServiceEnabled = accountServiceEnabled;
        this.defaultRole = normalizeRole(defaultRole);
        this.bootstrapAdminEmails = Set.copyOf(bootstrapAdminEmails);
        this.refreshTokens = refreshTokens;
        this.adminVerificationService = adminVerificationService;
    }

    @Override
    @SneakyThrows
    @Transactional
    public void register(RegistrationRequest registrationRequest) {
        validationService.validateConfirmPassword(registrationRequest);
        String normalizedEmail = registrationRequest.email().trim().toLowerCase(Locale.ROOT);

        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new UserAlreadyExistsException("User already exists");
        }

        boolean explicitRole = registrationRequest.role() != null && !registrationRequest.role().isBlank();
        String requestedRole = explicitRole ? normalizeRequestedRole(registrationRequest.role()) : defaultRole;
        boolean bootstrapAdmin = bootstrapAdminEmails.contains(normalizedEmail);
        boolean pendingAdminVerification = explicitRole && "ADMIN".equals(requestedRole) && !bootstrapAdmin;
        String assignedRole = bootstrapAdmin ? "ADMIN" : pendingAdminVerification ? "PENDING_ADMIN" : requestedRole;

        UserEntity user = UserEntity.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(registrationRequest.password2()))
                .role(assignedRole)
                .adminEmailVerified(!pendingAdminVerification)
                .build();

        user = userRepository.save(user);
        if (accountServiceEnabled) {
            kafkaService.getAccountId(DetailedRegistrationEvent.builder()
                    .firstName(registrationRequest.firstName())
                    .lastName(registrationRequest.lastName())
                    .email(user.getEmail())
                    .userId(user.getId())
                    .build());
        } else {
            user.setAccountId(user.getId());
            userRepository.save(user);
        }
        if (pendingAdminVerification) {
            if (adminVerificationService == null) {
                throw new IllegalStateException("Сервис подтверждения администратора не настроен");
            }
            adminVerificationService.start(user);
        }
        log.info("Registered user {} with role {}", user.getEmail(), user.getRole());
    }

    @Override
    public void verifyAdministrator(String token) {
        if (adminVerificationService == null) {
            throw new IllegalStateException("Сервис подтверждения администратора не настроен");
        }
        adminVerificationService.verify(token);
    }

    @Override
    public AuthResponse login(LoginRequest loginRequest) {
        UserEntity user = userRepository.findByEmail(loginRequest.email().trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UserNotFoundException("User is not found"));
        if (user.isBlocked()) {
            throw new UserNotFoundException("User account is blocked");
        }
        if ("PENDING_ADMIN".equalsIgnoreCase(user.getRole()) || !user.isAdminEmailVerified()) {
            throw new AdminVerificationRequiredException(
                    "Подтвердите адрес электронной почты по ссылке из письма, чтобы войти как администратор");
        }
        if(!passwordEncoder.matches(loginRequest.password(), user.getPassword())) {
            throw new PasswordNotMatchesException("Wrong password");
        }

        UUID accountId = user.getAccountId() == null ? user.getId() : user.getAccountId();
        return generateTokens(user, accountId);
    }


    @Override
    public RefreshTokenResponse refresh(RefreshTokenRequest refreshTokenRequest) {
        String oldRefreshToken = refreshTokenRequest.getRefreshToken();
        Claims claims = JwtUtil.extractAllClaims(oldRefreshToken);

        if (!"refresh".equals(claims.get(JwtUtil.TOKEN_TYPE_CLAIM))) {
            throw new InvalidTokenTypeException("Invalid token type");
        }

        String email = claims.get(JwtUtil.EMAIL_CLAIM, String.class);

        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        String redisKey = getRedisKey(user);
        RefreshTokenDto savedToken = refreshTokens.get(redisKey);
        if (savedToken == null) {
            throw new TokenNotFoundException("Refresh token not found in Redis");
        }
        if (savedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenNotFoundException("Refresh token is expired");
        }

        if (!oldRefreshToken.equals(savedToken.getToken())) {
            throw new TokenNotFoundException("Refresh token does not match");
        }

        String newAccessToken = JwtUtil.generateAccessToken(email, user.getAccountId(), user.getRole());
        String newRefreshToken = JwtUtil.generateRefreshToken(email, user.getAccountId(), user.getRole());

        RefreshTokenDto newDto = RefreshTokenDto.builder()
                .token(newRefreshToken)
                .expiresAt(Instant.now().plusMillis(JwtUtil.REFRESH_TOKEN_EXPIRATION))
                .build();

        refreshTokens.put(redisKey, newDto);

        return new RefreshTokenResponse(newAccessToken, newRefreshToken);
    }

    @Override
    public void logout(UserDetails user) {
        String email = user.getUsername();
        UUID userId = userRepository.getUserIdByEmail(email);
        refreshTokens.remove("refresh:" + userId);

        log.info("User {} logged out", email);
    }

    private AuthResponse generateTokens(UserEntity user, UUID accountId) {
        String accessToken = JwtUtil.generateAccessToken(user.getEmail(), accountId, user.getRole());
        String refreshToken = JwtUtil.generateRefreshToken(user.getEmail(), accountId, user.getRole());

        RefreshTokenDto dto = RefreshTokenDto.builder()
                .token(refreshToken)
                .expiresAt(Instant.now().plusMillis(JwtUtil.REFRESH_TOKEN_EXPIRATION))
                .build();

        String redisKey = getRedisKey(user);
        refreshTokens.put(redisKey, dto);

        return new AuthResponse(accessToken, refreshToken, user.getEmail(), Set.of(normalizeRole(user.getRole())));
    }

    private String getRedisKey(UserEntity user) {
        return "refresh:" + user.getId();
    }

    private String normalizeRole(String role) {
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase();
    }

    private String normalizeRequestedRole(String role) {
        String normalized = normalizeRole(role);
        if (!"USER".equals(normalized) && !"ADMIN".equals(normalized)) {
            throw new IllegalArgumentException("Допустимы только роли USER и ADMIN");
        }
        return normalized;
    }

    private static Set<String> parseEmails(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .map(email -> email.toLowerCase(Locale.ROOT))
                .filter(email -> !email.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

}
