package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
}
