package ru.skillbox.socialnetwork.auth.persistense;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByAdminVerificationTokenHash(String tokenHash);

    Optional<UserEntity> findByAccountId(UUID accountId);

    @Query("SELECT u.id FROM UserEntity u WHERE u.email = :email")
    UUID getUserIdByEmail(String email);
}
