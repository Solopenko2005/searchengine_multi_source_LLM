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
        assertTrue(service.isBoilerplateTitle(
                "Войти Регистрация Научные статьи Журналы Издательства Подписки"));
        assertTrue(service.isBoilerplateTitle("Understand your visitors with Statcounter"));
        assertTrue(service.isBoilerplateTitle("Subscribe to Global Stats by email"));
        assertTrue(service.isBoilerplateTitle("+7 (495) 117-45-83"));
        assertTrue(service.isBoilerplateTitle("Заключение"));
        assertTrue(service.isRelevantAndCleanTopic(group("Агроинженерия и пищевые технологии")));
        assertFalse(service.isBoilerplateTitle("Машинное обучение в сельском хозяйстве"));
    }

    private TopicGroup group(String title) {
        TopicGroup group = new TopicGroup();
        group.setTitle(title);
        group.setFrequency(3);
        group.setSiteCount(1);
        return group;
    }
}
