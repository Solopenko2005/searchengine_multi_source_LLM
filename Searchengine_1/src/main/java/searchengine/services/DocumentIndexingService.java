package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import searchengine.config.IndexingState;
import searchengine.model.*;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Сервис индексации источников информации, загруженных в виде отдельных файлов
 * документов (DOCX / PDF), а не веб-страниц.
 * <p>
 * Поддерживается загрузка сразу нескольких файлов за один раз. Каждый документ
 * извлекается (Apache POI для DOCX, Apache PDFBox для PDF), лемматизируется и
 * сохраняется как {@link Page}, привязанная к служебному источнику типа
 * {@link SourceType#DOCUMENT}, чтобы участвовать в общем поиске наравне со
 * страницами сайтов.
 * <p>
 * Пакетная обработка реагирует на общий запрос остановки индексации
 * ({@link IndexingState#isStopRequested()}): при остановке оставшиеся файлы
 * пропускаются.
 */
@Service
@RequiredArgsConstructor
public class DocumentIndexingService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentIndexingService.class);

    private static final String DOCUMENTS_SITE_URL = "local://documents";
    private static final String DOCUMENTS_SITE_NAME = "Загруженные документы";
    private static final Set<String> SUPPORTED_EXTENSIONS = new LinkedHashSet<>(List.of("docx", "pdf"));

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final DatabaseService databaseService;
    private final Lemmatizer lemmatizer;
    private final TopicExtractorService topicExtractorService;
    private final TopicRepository topicRepository;
    private final PageProcessor pageProcessor;
    private final IndexingState indexingState; // общее состояние индексации всех источников
    private final CurrentUserService currentUserService;

    /**
     * Индексирует пакет загруженных документов (DOCX/PDF). Обрабатывает файлы по очереди,
     * прерываясь при запросе остановки индексации, и возвращает сводный результат по каждому файлу.
     *
     * @param files массив загруженных файлов
     * @return карта результата: общий статус, текстовое сообщение и детализация по каждому файлу
     */
    public Map<String, Object> indexDocuments(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return error("Не выбрано ни одного файла");
        }

        List<Map<String, Object>> perFile = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int skipped = 0;
        String ownerId = currentUserService.getUserId();

        Site documentsSite = getOrCreateDocumentsSite();
        documentsSite.setStatus(Status.INDEXING);
        documentsSite.setStatusTime(LocalDateTime.now());
        documentsSite.setLastError(null);
        siteRepository.save(documentsSite);
        indexingState.beginOperation("Индексация документов", files.length);

        // регистрируем пакет как единицу работы в общем состоянии индексации,
        // чтобы кнопка «Остановить» и статистика видели активность
        indexingState.taskStarted();
        try {
            for (MultipartFile file : files) {
                String fileName = file != null ? file.getOriginalFilename() : null;

                if (indexingState.isStopRequested()) {
                    skipped++;
                    indexingState.itemFailed();
                    perFile.add(fileResult(fileName, false, "Пропущено: индексация остановлена"));
                    continue;
                }

                Map<String, Object> result = indexSingleDocument(file, ownerId);
                perFile.add(result);
                if (Boolean.TRUE.equals(result.get("result"))) {
                    success++;
                    indexingState.itemCompleted();
                } else {
                    failed++;
                    indexingState.itemFailed();
                }
            }
        } finally {
            try {
                Site finalDocumentsSite = getOrCreateDocumentsSite();
                finalDocumentsSite.setStatus(indexingState.isStopRequested()
                        ? Status.STOPPED
                        : (failed > 0 ? Status.FAILED : Status.INDEXED));
                finalDocumentsSite.setStatusTime(LocalDateTime.now());
                finalDocumentsSite.setLastError(indexingState.isStopRequested()
                        ? "Индексация остановлена пользователем"
                        : (failed > 0 ? "Часть документов не была проиндексирована" : null));
                siteRepository.save(finalDocumentsSite);
            } finally {
                indexingState.taskFinished();
            }
        }

        Map<String, Object> response = new java.util.HashMap<>();
        // общий успех, если хотя бы один документ проиндексирован и не было явных ошибок по остальным
        response.put("result", success > 0);
        response.put("message", String.format(
                "Файлов получено: %d, проиндексировано: %d, с ошибкой: %d, пропущено: %d",
                files.length, success, failed, skipped));
        response.put("total", files.length);
        response.put("success", success);
        response.put("failed", failed);
        response.put("skipped", skipped);
        response.put("files", perFile);
        if (success == 0 && failed > 0) {
            response.put("error", "Не удалось проиндексировать ни один документ");
        }
        return response;
    }

    /**
     * Индексирует один загруженный документ. Метод самостоятельно определяет формат по расширению.
     */
    private Map<String, Object> indexSingleDocument(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty()) {
            return fileResult(file != null ? file.getOriginalFilename() : null, false, "Файл пуст");
        }

        String originalName = file.getOriginalFilename();
        String extension = extractExtension(originalName);

        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            return fileResult(originalName, false, "Поддерживаются только форматы DOCX и PDF");
        }

        String extractedText;
        try {
            extractedText = extractText(file, extension);
        } catch (Exception e) {
            logger.error("Не удалось извлечь текст из документа {}: {}", originalName, e.getMessage(), e);
            return fileResult(originalName, false, "Не удалось прочитать документ: " + e.getMessage());
        }

        if (extractedText == null || extractedText.trim().isEmpty()) {
            return fileResult(originalName, false, "В документе нет текста для индексации");
        }

        try {
            Site documentsSite = getOrCreateDocumentsSite();
            String path = "/" + sanitizeFileName(originalName);

            // если документ с таким именем уже был загружен — полностью удаляем старую версию
            pageRepository.findBySiteAndPathAndOwnerId(documentsSite.getId(), path, ownerId)
                    .ifPresent(pageProcessor::deletePageInfo);

            String html = wrapAsHtml(originalName, extractedText);

            Page page = new Page();
            page.setSite(documentsSite);
            page.setPath(path);
            page.setCode(200);
            page.setContent(html);
            page.setOriginalFileName(originalName);
            page.setOwnerId(ownerId);
            page.setTopicCount(0);

            databaseService.savePage(page);

            indexLemmas(documentsSite, page, extractedText);
            int topicsCount = extractAndSaveTopics(page, html, documentsSite);

            logger.info("Документ '{}' проиндексирован (страница {}, тем: {})",
                    originalName, page.getId(), topicsCount);

            Map<String, Object> ok = fileResult(originalName, true, "Проиндексирован");
            ok.put("path", path);
            ok.put("topics", topicsCount);
            return ok;
        } catch (Exception e) {
            logger.error("Ошибка при индексации документа {}: {}", originalName, e.getMessage(), e);
            return fileResult(originalName, false, "Ошибка индексации: " + e.getMessage());
        }
    }

    private Site getOrCreateDocumentsSite() {
        return siteRepository.findByUrl(DOCUMENTS_SITE_URL)
                .orElseGet(() -> {
                    Site site = new Site();
                    site.setUrl(DOCUMENTS_SITE_URL);
                    site.setName(DOCUMENTS_SITE_NAME);
                    site.setSourceType(SourceType.DOCUMENT);
                    site.setStatus(Status.INDEXED);
                    site.setStatusTime(LocalDateTime.now());
                    return siteRepository.save(site);
                });
    }

    private String extractText(MultipartFile file, String extension) throws IOException {
        switch (extension) {
            case "docx":
                try (XWPFDocument document = new XWPFDocument(file.getInputStream());
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            case "pdf":
                try (PDDocument document = PDDocument.load(file.getInputStream())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    return stripper.getText(document);
                }
            default:
                throw new IllegalArgumentException("Неподдерживаемый формат файла: " + extension);
        }
    }

    private void indexLemmas(Site site, Page page, String text) {
        Map<String, Integer> lemmaMap = lemmatizer.extractLemmasWithRank(text);
        for (Map.Entry<String, Integer> entry : lemmaMap.entrySet()) {
            if (indexingState.isStopRequested()) {
                throw new IllegalStateException("Индексация остановлена пользователем");
            }
            String lemmaText = entry.getKey();
            Integer rank = entry.getValue();
            Lemma savedLemma = databaseService.saveLemma(lemmaText, site);
            if (savedLemma != null) {
                SearchIndex index = new SearchIndex();
                index.setPage(page);
                index.setLemma(savedLemma);
                index.setRanking(rank);
                databaseService.saveSearchIndex(index);
            }
        }
    }

    private int extractAndSaveTopics(Page page, String html, Site site) {
        try {
            topicRepository.deleteByPageId(page.getId());
            List<Topic> topics = topicExtractorService.extractTopics(page, html);

            for (Topic topic : topics) {
                topic.setPage(page);
                topic.setSite(site);
                topic.setLemmaCount(calculateLemmaCount(topic.getContent()));
                topicRepository.save(topic);
            }

            page.setTopicCount(topics.size());
            pageRepository.save(page);

            return topics.size();
        } catch (Exception e) {
            logger.warn("Не удалось извлечь темы из документа {}: {}", page.getPath(), e.getMessage());
            return 0;
        }
    }

    private int calculateLemmaCount(String content) {
        if (content == null || content.trim().isEmpty()) {
            return 0;
        }
        return lemmatizer.extractLemmasWithRank(content).values().stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    private String extractExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeFileName(String originalName) {
        String name = (originalName == null || originalName.trim().isEmpty()) ? "document" : originalName.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name.replaceAll("[^a-zA-Zа-яА-ЯёЁ0-9._-]", "_");
    }

    private String wrapAsHtml(String title, String text) {
        String safeTitle = escapeHtml(title == null ? "Документ" : title);
        String safeBody = escapeHtml(text).replace("\r\n", "\n").replace("\n", "<br/>");
        return "<html><head><title>" + safeTitle + "</title></head><body>" + safeBody + "</body></html>";
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private Map<String, Object> fileResult(String fileName, boolean ok, String message) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("fileName", fileName);
        map.put("result", ok);
        if (ok) {
            map.put("message", message);
        } else {
            map.put("error", message);
        }
        return map;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("result", false);
        map.put("error", message);
        return map;
    }
}
