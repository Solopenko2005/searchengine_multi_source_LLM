package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

/**
 * Обзор популярных тематик по загруженным документам.
 */
@Data
public class TopicsSummaryResponse {

    private boolean result;

    private String error;

    /**
     * Связный текстовый обзор популярных тем (генерируется LLM,
     * либо кратко формируется локально в резервном режиме).
     */
    private String summary;

    /**
     * Список тем со статистикой.
     */
    private List<TopicItem> topics = new ArrayList<>();

    /**
     * true — обзор сгенерирован языковой моделью.
     */
    private boolean usedLlm;

    /** True when the result was read from the persistent topic cache. */
    private boolean cached;

    /** Cached data can still be displayed while a background refresh is running. */
    private boolean stale;

    private boolean refreshing;

    private LocalDateTime updatedAt;
}
