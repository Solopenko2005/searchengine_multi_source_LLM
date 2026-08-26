package ru.skillbox.socialnetwork.auth.security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;
import ru.skillbox.socialnetwork.auth.exception.UserNotFoundException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CustomUserDetailsServiceTest {
    private UserRepository userRepository;
    private CustomUserDetailsService userDetailsService;

    private final String email = "test@example.com";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDetailsService = new CustomUserDetailsService(userRepository);
    }

    @Test
    void loadUserByUsername_shouldReturnUserDetails_whenUserExists() {
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .password("encoded-password")
                .accountId(UUID.randomUUID())
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        assertNotNull(userDetails);
        assertEquals(email, userDetails.getUsername());
        assertEquals("encoded-password", userDetails.getPassword());
    }

    @Test
    void loadUserByUsername_shouldThrowException_whenUserNotFound() {
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        Exception exception = assertThrows(UserNotFoundException.class, () -> userDetailsService.loadUserByUsername(email));

        assertTrue(exception.getMessage().contains("User with email " + email));
    }
}
