package searchengine.dto.assistant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Источник (документ/страница), использованный при формировании ответа.
 * Отображается пользователю как ссылка на литературу.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssistantSource {

    /**
     * Порядковый номер источника ([1], [2], ...), на который ссылается ответ.
     */
    private int index;

    /**
     * Заголовок документа или имя загруженного файла.
     */
    private String title;

    /**
     * Название источника (сайт/журнал/коллекция).
     */
    private String source;

    /**
     * Ссылка на документ (URL страницы или путь к файлу).
     */
    private String url;

    /**
     * Краткий релевантный фрагмент текста.
     */
    private String snippet;
}
