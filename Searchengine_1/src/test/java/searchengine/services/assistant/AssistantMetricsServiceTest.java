package searchengine.services.assistant;

import org.junit.jupiter.api.Test;
import searchengine.config.assistant.AssistantConfig;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantMetricsServiceTest {
    @Test
    void reportsPercentilesAndCompliantSloForHealthyWindow() {
        AssistantConfig config = new AssistantConfig();
        config.getSlo().setMinimumSamples(3);
        AssistantMetricsService metrics = new AssistantMetricsService(config);
        metrics.record(100, 1000, 300, 1200, false, false);
        metrics.record(200, 1200, 400, 1500, false, false);
        metrics.record(300, 1400, 500, 1800, false, false);

        Map<String, Object> snapshot = metrics.snapshot();

        assertThat(snapshot).containsEntry("retrievalP95Ms", 300L)
                .containsEntry("timeToFirstTokenP95Ms", 500L)
                .containsEntry("totalP95Ms", 1800L);
        assertThat((Map<String, Object>) snapshot.get("slo"))
                .containsEntry("state", "compliant")
                .containsEntry("compliant", true);
    }

    @Test
    void reportsViolationWhenFailureBudgetIsExceeded() {
        AssistantConfig config = new AssistantConfig();
        config.getSlo().setMinimumSamples(2);
        AssistantMetricsService metrics = new AssistantMetricsService(config);
        metrics.record(100, 1000, 300, 1200, false, false);
        metrics.record(100, 1000, 300, 1200, true, true);

        Map<String, Object> slo = (Map<String, Object>) metrics.snapshot().get("slo");

        assertThat(slo).containsEntry("state", "violated")
                .containsEntry("compliant", false);
    }

    @Test
    void reportsViolationWhenFallbackBudgetIsExceededEvenWithoutProviderError() {
        AssistantConfig config = new AssistantConfig();
        config.getSlo().setMinimumSamples(2);
        AssistantMetricsService metrics = new AssistantMetricsService(config);
        metrics.record(100, 1000, 300, 1200, false, false);
        metrics.record(100, 0, 0, 150, false, true);

        Map<String, Object> snapshot = metrics.snapshot();
        Map<String, Object> slo = (Map<String, Object>) snapshot.get("slo");
        Map<String, Object> fallbackRate = (Map<String, Object>) slo.get("fallbackRate");

        assertThat(snapshot).containsEntry("timeToFirstTokenP50Ms", 300L);
        assertThat(slo).containsEntry("state", "violated").containsEntry("compliant", false);
        assertThat(fallbackRate).containsEntry("pass", false).containsEntry("actual", 0.5d);
    }
}
