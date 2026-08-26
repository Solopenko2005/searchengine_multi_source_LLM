package searchengine.services.scientific;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.scientific.ScientificArticleDto;
import searchengine.services.CurrentUserService;
import searchengine.services.assistant.AssistantProfileService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Builds publication recommendations from the topics explicitly detected by the Assistant. */
@Service
@RequiredArgsConstructor
public class ScientificRecommendationService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int PROVIDER_TIMEOUT_SECONDS = 8;

    private final AssistantProfileService profileService;
    private final CurrentUserService currentUserService;
    private final ScientificCatalogService catalogService;
    private final Map<String, CachedRecommendation> cache = new ConcurrentHashMap<>();

    public List<String> topics() {
        return profileService.getDetectedTopics().stream().limit(3).toList();
    }

    public Recommendation recommend(int requestedLimit) {
        int limit = Math.max(1, Math.min(10, requestedLimit));
        List<String> topics = topics();
        if (topics.isEmpty()) return new Recommendation("", List.of(), List.of());

        String cacheKey = currentUserService.getUserId() + "|" + limit + "|" + String.join("|", topics);
        CachedRecommendation cached = cache.get(cacheKey);
        if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.value();
        }

        String query = topics.stream().collect(Collectors.joining(" "));
        if (query.length() > 240) query = query.substring(0, 240);
        final String searchQuery = query;
        List<CompletableFuture<List<ScientificArticleDto>>> searches = List.of(
                searchAsync("crossref", searchQuery, limit),
                searchAsync("europepmc", searchQuery, limit));
        CompletableFuture.allOf(searches.toArray(CompletableFuture[]::new)).join();

        Map<String, ScientificArticleDto> unique = new LinkedHashMap<>();
        for (CompletableFuture<List<ScientificArticleDto>> search : searches) {
            for (ScientificArticleDto article : search.getNow(List.of())) {
                String key = article.getUrl() == null || article.getUrl().isBlank()
                        ? String.valueOf(article.getTitle()).toLowerCase(Locale.ROOT)
                        : article.getUrl().toLowerCase(Locale.ROOT);
                unique.putIfAbsent(key, article);
                if (unique.size() >= limit) break;
            }
            if (unique.size() >= limit) break;
        }

        Recommendation result = new Recommendation(topics.get(0), topics,
                new ArrayList<>(unique.values()));
        if (cache.size() > 200) {
            Instant expiry = Instant.now().minus(CACHE_TTL);
            cache.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(expiry));
        }
        cache.put(cacheKey, new CachedRecommendation(Instant.now(), result));
        return result;
    }

    private CompletableFuture<List<ScientificArticleDto>> searchAsync(String provider,
                                                                       String query,
                                                                       int limit) {
        return CompletableFuture.supplyAsync(() -> catalogService.search(provider, query, "all", limit))
                .completeOnTimeout(List.of(), PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> List.of());
    }

    public record Recommendation(String topic, List<String> topics,
                                 List<ScientificArticleDto> articles) {
    }

    private record CachedRecommendation(Instant createdAt, Recommendation value) {
    }
}
