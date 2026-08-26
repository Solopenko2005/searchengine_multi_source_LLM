package ru.skillbox.socialnetwork.auth.persistense;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Data
@Entity
@Builder
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
public class UserEntity {
    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;
    private String email;
    private String password;
    private UUID accountId;

    @Column(nullable = false)
    private String role;

    @Column(name = "isblocked")
    private boolean isBlocked;

    @Column(name = "admin_email_verified", nullable = false)
    private boolean adminEmailVerified;

    @Column(name = "admin_verification_token_hash", length = 64)
    private String adminVerificationTokenHash;

    @Column(name = "admin_verification_expires_at")
    private Instant adminVerificationExpiresAt;
}
