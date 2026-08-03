package searchengine.model;

/**
 * Тип источника информации.
 * WEBSITE  — сайт, который обходится краулером по ссылкам (Jsoup).
 * DOCUMENT — отдельный загруженный пользователем документ (DOCX/PDF),
 *            содержимое которого извлекается и индексируется целиком.
 */
public enum SourceType {
    WEBSITE, DOCUMENT
}
