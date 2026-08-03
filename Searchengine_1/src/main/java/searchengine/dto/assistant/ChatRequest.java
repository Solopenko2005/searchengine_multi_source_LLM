package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Запрос пользователя к ассистенту.
 */
@Data
public class ChatRequest {

    /**
     * Текущий вопрос пользователя.
     */
    private String message;

    /**
     * Предыдущие сообщения диалога (для сохранения контекста беседы).
     * Может быть пустым.
     */
    private List<ChatMessage> history = new ArrayList<>();

    /**
     * Необязательный фильтр по источнику (URL сайта). Пусто — по всем источникам.
     */
    private String site;

    /** Пользовательская роль/область внимания ассистента для текущей рабочей области. */
    private String profileInstructions;

    /** Необязательное ограничение RAG конкретными документами пользователя. */
    private List<Integer> documentIds = new ArrayList<>();
}
