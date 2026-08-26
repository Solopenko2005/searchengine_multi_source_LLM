package searchengine.services.scientific;

import org.junit.jupiter.api.Test;
import searchengine.dto.scientific.ScientificArticleDto;
import searchengine.services.CurrentUserService;
import searchengine.services.assistant.AssistantProfileService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScientificRecommendationServiceTest {

    @Test
    void recommendsFromTopicsPreviouslyDetectedByAssistant() {
        AssistantProfileService profiles = mock(AssistantProfileService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ScientificCatalogService catalog = mock(ScientificCatalogService.class);
        ScientificRecommendationService service = new ScientificRecommendationService(
                profiles, currentUser, catalog);
        when(profiles.getDetectedTopics()).thenReturn(List.of("Генетика растений", "Селекция"));
        when(currentUser.getUserId()).thenReturn("alice");
        ScientificArticleDto article = new ScientificArticleDto();
        article.setTitle("Related research");
        article.setUrl("https://example.test/article");
        when(catalog.search("crossref", "Генетика растений Селекция", "all", 6))
                .thenReturn(List.of(article));
        when(catalog.search("europepmc", "Генетика растений Селекция", "all", 6))
                .thenReturn(List.of());

        ScientificRecommendationService.Recommendation result = service.recommend(6);

        assertThat(result.topic()).isEqualTo("Генетика растений");
        assertThat(result.topics()).containsExactly("Генетика растений", "Селекция");
        assertThat(result.articles()).containsExactly(article);
    }

    @Test
    void doesNotSearchCatalogsBeforeAssistantDetectsTopics() {
        AssistantProfileService profiles = mock(AssistantProfileService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ScientificCatalogService catalog = mock(ScientificCatalogService.class);
        ScientificRecommendationService service = new ScientificRecommendationService(
                profiles, currentUser, catalog);
        when(profiles.getDetectedTopics()).thenReturn(List.of());

        ScientificRecommendationService.Recommendation result = service.recommend(6);

        assertThat(result.topic()).isBlank();
        assertThat(result.topics()).isEmpty();
        assertThat(result.articles()).isEmpty();
        verify(catalog, never()).search(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
