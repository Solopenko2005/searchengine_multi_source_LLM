package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.AssistantProfile;

import java.util.Optional;

public interface AssistantProfileRepository extends JpaRepository<AssistantProfile, Long> {
    Optional<AssistantProfile> findByOwnerId(String ownerId);
}
