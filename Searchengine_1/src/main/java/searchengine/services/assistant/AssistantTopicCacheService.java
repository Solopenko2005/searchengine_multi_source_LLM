package searchengine.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.assistant.TopicsSummaryResponse;
import searchengine.model.AssistantTopicCache;
import searchengine.repository.AssistantTopicCacheRepository;
import searchengine.repository.PageRepository;
import searchengine.services.CurrentUserService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AssistantTopicCacheService {
    private static final String CACHE_ALGORITHM_VERSION = "topic-analysis-v2";

    private final AssistantTopicCacheRepository repository;
    private final PageRepository pageRepository;
    private final CurrentUserService currentUserService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public String scopeHash(List<Integer> sourceIds, String profileInstructions) {
        List<Integer> sorted = new ArrayList<>(sourceIds == null ? List.of() : sourceIds);
        Collections.sort(sorted);
        PageRepository.ScopeRevision revision = sorted.isEmpty() ? null : pageRepository.scopeRevision(sorted);
        String material = CACHE_ALGORITHM_VERSION + "|" + sorted + "|"
                + (profileInstructions == null ? "" : profileInstructions.trim())
                + "|" + llmClient.getConfiguredModel()
                + "|" + (revision == null ? "0|0|0" : revision.getPageCount() + "|"
                + revision.getMaxPageId() + "|" + revision.getSumPageIds());
        return sha256(material);
    }

    @Transactional(readOnly = true)
    public Optional<TopicsSummaryResponse> read(String scopeHash) {
        return repository.findById(currentUserService.getUserId())
                .filter(cache -> cache.getScopeHash().equals(scopeHash))
                .flatMap(cache -> deserialize(cache, false));
    }

    @Transactional(readOnly = true)
    public Optional<TopicsSummaryResponse> readLatest() {
        return repository.findById(currentUserService.getUserId())
                .filter(cache -> cacheModel().equals(cache.getModel()))
                .flatMap(cache -> deserialize(cache, true));
    }

    @Transactional
    public void save(String scopeHash, TopicsSummaryResponse response) {
        try {
            AssistantTopicCache cache = repository.findById(currentUserService.getUserId())
                    .orElseGet(AssistantTopicCache::new);
            cache.setOwnerId(currentUserService.getUserId());
            cache.setScopeHash(scopeHash);
            cache.setPayload(objectMapper.writeValueAsString(response));
            cache.setModel(cacheModel());
            cache.setUpdatedAt(LocalDateTime.now());
            repository.save(cache);
        } catch (Exception exception) {
            throw new IllegalStateException("Не удалось сохранить кеш тематик", exception);
        }
    }

    private Optional<TopicsSummaryResponse> deserialize(AssistantTopicCache cache, boolean stale) {
        try {
            TopicsSummaryResponse response = objectMapper.readValue(cache.getPayload(), TopicsSummaryResponse.class);
            response.setCached(true);
            response.setStale(stale);
            response.setUpdatedAt(cache.getUpdatedAt());
            return Optional.of(response);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String cacheModel() {
        return llmClient.getConfiguredModel() + "|" + CACHE_ALGORITHM_VERSION;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
