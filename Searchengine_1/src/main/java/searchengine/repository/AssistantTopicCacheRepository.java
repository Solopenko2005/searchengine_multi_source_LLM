package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.AssistantTopicCache;

public interface AssistantTopicCacheRepository extends JpaRepository<AssistantTopicCache, String> {
}
