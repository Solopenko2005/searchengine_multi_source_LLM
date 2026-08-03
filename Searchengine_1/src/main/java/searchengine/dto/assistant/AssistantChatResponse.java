package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Ответ ассистента на вопрос пользователя.
 */
@Data
public class AssistantChatResponse {

    private boolean result;

    private String error;

    /**
     * Текст ответа (может содержать ссылки на источники вида [1], [2]).
     */
    private String answer;

    /**
     * Список использованных источников (литературы).
     */
    private List<AssistantSource> sources = new ArrayList<>();

    /**
     * true — ответ сгенерирован языковой моделью;
     * false — резервный режим (модель не настроена/недоступна).
     */
    private boolean usedLlm;
}
