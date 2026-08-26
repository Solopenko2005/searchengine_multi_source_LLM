package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import searchengine.dto.scientific.ScientificArticleDto;
import searchengine.services.scientific.ScientificArticleIndexingService;
import searchengine.services.scientific.ScientificCatalogService;
import searchengine.services.scientific.ScientificRecommendationService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scientific")
@RequiredArgsConstructor
public class ScientificCatalogController {

    private final ScientificCatalogService catalogService;
    private final ScientificArticleIndexingService indexingService;
    private final ScientificRecommendationService recommendationService;

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return catalogService.catalog();
    }

    @GetMapping("/articles")
    public ResponseEntity<Map<String, Object>> articles(@RequestParam String provider,
                                                         @RequestParam String query,
                                                         @RequestParam(defaultValue = "all") String category,
                                                         @RequestParam(defaultValue = "10") int limit) {
        try {
            List<ScientificArticleDto> data = catalogService.search(provider, query, category, limit);
            return ResponseEntity.ok(Map.of("result", true, "data", data, "count", data.size()));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            return ResponseEntity.status(502).body(Map.of("result", false,
                    "error", "Научный каталог временно недоступен: " + exception.getMessage()));
        }
    }

    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> recommendations(
            @RequestParam(defaultValue = "6") int limit) {
        ScientificRecommendationService.Recommendation recommendation =
                recommendationService.recommend(limit);
        return ResponseEntity.ok(Map.of(
                "result", true,
                "topic", recommendation.topic(),
                "topics", recommendation.topics(),
                "data", recommendation.articles(),
                "count", recommendation.articles().size()));
    }

    @GetMapping("/recommendation-topics")
    public Map<String, Object> recommendationTopics() {
        List<String> topics = recommendationService.topics();
        return Map.of("result", true, "topics", topics, "count", topics.size());
    }

    @PostMapping("/articles/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody ScientificArticleDto article) {
        Map<String, Object> result = indexingService.submit(article);
        return Boolean.TRUE.equals(result.get("result"))
                ? ResponseEntity.accepted().body(result) : ResponseEntity.badRequest().body(result);
    }
}
