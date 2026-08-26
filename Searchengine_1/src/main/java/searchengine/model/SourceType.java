package searchengine.model;

/**
 * Тип источника информации.
 * WEBSITE  — сайт, который обходится краулером по ссылкам (Jsoup).
 * DOCUMENT — отдельный загруженный пользователем документ (DOCX/PDF),
 *            содержимое которого извлекается и индексируется целиком.
 * SCIENTIFIC_ARTICLE — научная публикация, добавленная через каталог
 *                      научных источников.
 */
public enum SourceType {
    WEBSITE, DOCUMENT, SCIENTIFIC_ARTICLE
}
