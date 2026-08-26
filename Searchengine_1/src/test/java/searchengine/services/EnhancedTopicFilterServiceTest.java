package searchengine.services;

import org.junit.jupiter.api.Test;
import searchengine.model.TopicGroup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnhancedTopicFilterServiceTest {
    private final EnhancedTopicFilterService service = new EnhancedTopicFilterService();

    @Test
    void removesNavigationAndPublicationBoilerplate() {
        assertFalse(service.isRelevantAndCleanTopic(group("Последние новости")));
        assertFalse(service.isRelevantAndCleanTopic(group("Популярное")));
        assertFalse(service.isRelevantAndCleanTopic(group("Для цитирования:")));
        assertFalse(service.isRelevantAndCleanTopic(group("Использование куки-файлов")));
        assertTrue(service.isRelevantAndCleanTopic(group("Агроинженерия и пищевые технологии")));
    }

    private TopicGroup group(String title) {
        TopicGroup group = new TopicGroup();
        group.setTitle(title);
        group.setFrequency(3);
        group.setSiteCount(1);
        return group;
    }
}
