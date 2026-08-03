package searchengine.config.assistant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Конфигурация LLM-ассистента.
 * <p>
 * Ассистент подключается к OpenAI Responses API.
 * Адрес, ключ и модель задаются переменными окружения, а секреты не хранятся в репозитории.
 */
@Data
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantConfig {

    /**
     * Настройки подключения к языковой модели.
     */
    private Llm llm = new Llm();

    /**
     * Настройки поиска контекста по документам (RAG).
     */
    private Rag rag = new Rag();

    @Data
    public static class Llm {
        /** Провайдер API. Сейчас полноценно поддерживается OpenAI Responses API. */
        private String provider = "openai";

        /**
         * Включён ли вызов внешней модели. Если false или ключ пуст —
         * ассистент работает в резервном (локальном) режиме.
         */
        private boolean enabled = true;

        /**
         * Базовый URL OpenAI API (без /responses).
         */
        private String baseUrl = "https://api.openai.com/v1";

        /**
         * API-ключ. Рекомендуется задавать через переменную окружения.
         */
        private String apiKey = "";

        /**
         * Идентификатор модели. По умолчанию используется сбалансированная gpt-5.6-terra.
         */
        private String model = "gpt-5.6-terra";

        /**
         * Интенсивность рассуждения модели: none, low, medium, high и т.п.
         */
        private String reasoningEffort = "low";

        /**
         * Максимальное количество токенов в ответе.
         */
        private int maxOutputTokens = 1600;

        /**
         * Таймаут запроса к модели, секунды.
         */
        private int timeoutSeconds = 60;

        /** Число повторов при 429 и временных ошибках провайдера. */
        private int retryAttempts = 3;
    }

    @Data
    public static class Rag {
        /**
         * Сколько документов-фрагментов передавать модели в контекст.
         */
        private int maxDocuments = 6;

        /**
         * Максимальная длина текста одного документа в контексте (символы).
         */
        private int maxCharsPerDocument = 2000;

        /** Максимальное число реплик истории, добавляемых в запрос. */
        private int maxHistoryMessages = 8;

        /** Общий предохранитель размера входа до отправки во внешний API. */
        private int maxInputChars = 50000;

        /** Сколько документов одновременно использовать при семантическом анализе тематик. */
        private int topicDocumentLimit = 30;
    }
}
