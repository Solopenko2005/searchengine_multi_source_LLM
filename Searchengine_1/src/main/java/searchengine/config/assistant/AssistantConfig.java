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

    /** Local semantic index built from the user's sources. */
    private Embedding embedding = new Embedding();

    /** Измеримые пороги готовности интерактивного LLM-контура. */
    private Slo slo = new Slo();

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

        /** Ограничивает одновременную генерацию, чтобы локальная модель не уходила в перегрузку. */
        private int maxConcurrentRequests = 2;

        /** Сколько запрос может ждать свободный слот LLM. */
        private long queueTimeoutMillis = 500;

        /** После скольких последовательных ошибок временно разомкнуть цепь вызовов. */
        private int circuitFailureThreshold = 3;

        /** Пауза перед пробным запросом после срабатывания circuit breaker. */
        private int circuitCooldownSeconds = 30;

        /** TTL точного кэша ответов на идентичный вопрос и идентичный контекст. */
        private int responseCacheTtlSeconds = 300;

        private int responseCacheMaxEntries = 500;
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

        /** Total document excerpt budget for a CPU-hosted local language model. */
        private int localContextChars = 1200;

        /** Сколько документов одновременно использовать при семантическом анализе тематик. */
        private int topicDocumentLimit = 80;

        /** Minimum LLM confidence required to expose a detected topic. */
        private double topicMinConfidence = 0.65;

        /** Number of lexical/vector candidates merged before final context selection. */
        private int candidateDocuments = 32;

        /** Не позволяет одному большому сайту вытеснить все остальные источники из RAG-контекста. */
        private int maxDocumentsPerSource = 2;
    }

    @Data
    public static class Embedding {
        /** Embeddings are optional: lexical search remains available while they are built. */
        private boolean enabled = true;

        /** OpenAI-compatible endpoint. Empty means reuse assistant.llm.base-url. */
        private String baseUrl = "";

        /** Empty means reuse assistant.llm.api-key. */
        private String apiKey = "";

        private String model = "text-embedding-nomic-embed-text-v1.5";

        /** Approximate chunk size in characters; paragraphs are kept intact where possible. */
        private int chunkChars = 2600;

        private int chunkOverlapChars = 320;
        private int batchSize = 64;
        private int scanBatchSize = 128;
        private long scanDelayMillis = 1000;
        private int timeoutSeconds = 60;
        private String indexPath = "data/assistant-vectors";

        /** Повторно используем embedding одинакового поискового запроса. */
        private int queryCacheTtlSeconds = 900;
        private int queryCacheMaxEntries = 1000;

        /** Автоматически возвращать временно упавшие пакеты в очередь. */
        private boolean autoRetryFailed = true;
        private int failedRetryDelaySeconds = 300;
    }

    @Data
    public static class Slo {
        private int sampleWindow = 512;
        private int minimumSamples = 10;
        private long retrievalP95Millis = 2_000;
        private long timeToFirstTokenP95Millis = 5_000;
        private long totalP95Millis = 20_000;
        private double maximumFailureRate = 0.01;
        private double maximumFallbackRate = 0.01;
    }
}
