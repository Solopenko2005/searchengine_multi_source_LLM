package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;
import searchengine.config.IndexingState;
import searchengine.model.*;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.PreDestroy;

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

    @Value("${indexing-settings.documents.enabled:true}")
    private boolean documentIndexingEnabled;

    @Value("${indexing-settings.documents.max-file-size-bytes:52428800}")
    private long maxFileSizeBytes;

    // The shared document Site/Lemma aggregate is updated transactionally; serial execution
    // prevents frequency races while still keeping uploads outside HTTP request threads.
    private final ExecutorService documentExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, DocumentJob> jobsByOwner = new ConcurrentHashMap<>();
    private final ThreadLocal<DocumentJob> currentJob = new ThreadLocal<>();

    /** Accepts files, snapshots their bytes and starts a cancellable background job. */
    public Map<String, Object> submitDocuments(MultipartFile[] files) {
        if (!documentIndexingEnabled) {
            return error("Индексация документов отключена настройкой DOCUMENT_INDEXING_ENABLED");
        }
        if (files == null || files.length == 0) {
            return error("Не выбран ни один файл");
        }

        String ownerId = currentUserService.getUserId();
        DocumentJob previous = jobsByOwner.get(ownerId);
        if (previous != null && previous.isRunning()) {
            return error("Индексация документов уже выполняется");
        }

        MultipartFile[] snapshots = new MultipartFile[files.length];
        try {
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                if (file == null || file.isEmpty()) {
                    return error("Один из загруженных файлов пуст");
                }
                if (file.getSize() > maxFileSizeBytes) {
                    return error("Файл " + file.getOriginalFilename() + " превышает допустимый размер");
                }
                snapshots[i] = new ByteArrayMultipartFile(file.getName(), file.getOriginalFilename(),
                        file.getContentType(), file.getBytes());
            }
        } catch (IOException e) {
            return error("Не удалось принять загруженные файлы: " + e.getMessage());
        }

        DocumentJob job = new DocumentJob(ownerId, files.length);
        jobsByOwner.put(ownerId, job);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        documentExecutor.submit(() -> runJob(job, snapshots, authentication));

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("result", true);
        response.put("accepted", true);
        response.put("jobId", job.id);
        response.put("state", job.state);
        response.put("total", job.total);
        response.put("message", "Документы приняты, индексация запущена в фоне");
        return response;
    }

    public Map<String, Object> stopCurrentUserJob() {
        String ownerId = currentUserService.getUserId();
        DocumentJob job = jobsByOwner.get(ownerId);
        if (job == null || !job.isRunning()) {
            return error("Активная индексация документов не найдена");
        }
        job.stopRequested.set(true);
        job.state = "STOPPING";
        return Map.of("result", true, "jobId", job.id, "state", job.state,
                "message", "Остановка индексации запрошена");
    }

    public Map<String, Object> currentUserStatus() {
        String ownerId = currentUserService.getUserId();
        DocumentJob job = jobsByOwner.get(ownerId);
        long indexedDocuments = pageRepository.findBySiteSourceTypeOrderByIdDesc(SourceType.DOCUMENT).stream()
                .filter(currentUserService::canAccess)
                .count();
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("result", true);
        status.put("enabled", documentIndexingEnabled);
        status.put("indexedDocuments", indexedDocuments);
        if (job == null) {
            status.put("inProgress", false);
            status.put("state", "IDLE");
            status.put("ready", indexedDocuments > 0);
            return status;
        }
        status.put("jobId", job.id);
        status.put("state", job.state);
        status.put("inProgress", job.isRunning());
        status.put("ready", !job.isRunning() && "COMPLETED".equals(job.state));
        status.put("total", job.total);
        status.put("completed", job.completed.get());
        status.put("failed", job.failed.get());
        status.put("message", job.message);
        return status;
    }

    private void runJob(DocumentJob job, MultipartFile[] files, Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        currentJob.set(job);
        job.state = "RUNNING";
        try {
            Map<String, Object> result = indexDocuments(files);
            if (job.stopRequested.get()) {
                job.state = "STOPPED";
            } else if (((Number) result.getOrDefault("failed", 0)).intValue() > 0) {
                job.state = "FAILED";
            } else {
                job.state = "COMPLETED";
            }
            job.message = String.valueOf(result.getOrDefault("message", ""));
        } catch (Exception e) {
            job.state = job.stopRequested.get() ? "STOPPED" : "FAILED";
            job.message = e.getMessage();
            logger.error("Ошибка фоновой индексации документов пользователя {}", job.ownerId, e);
        } finally {
            currentJob.remove();
            SecurityContextHolder.clearContext();
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        documentExecutor.shutdownNow();
    }

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

                if (shouldStop()) {
                    skipped++;
                    indexingState.itemFailed();
                    updateJobProgress(false);
                    perFile.add(fileResult(fileName, false, "Пропущено: индексация остановлена"));
                    continue;
                }

                Map<String, Object> result = indexSingleDocument(file, ownerId);
                perFile.add(result);
                if (Boolean.TRUE.equals(result.get("result"))) {
                    success++;
                    indexingState.itemCompleted();
                    updateJobProgress(true);
                } else {
                    failed++;
                    indexingState.itemFailed();
                    updateJobProgress(false);
                }
            }
        } finally {
            try {
                Site finalDocumentsSite = getOrCreateDocumentsSite();
                finalDocumentsSite.setStatus(shouldStop()
                        ? Status.STOPPED
                        : (failed > 0 ? Status.FAILED : Status.INDEXED));
                finalDocumentsSite.setStatusTime(LocalDateTime.now());
                finalDocumentsSite.setLastError(shouldStop()
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
            if (shouldStop()) {
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

    private boolean shouldStop() {
        DocumentJob job = currentJob.get();
        return indexingState.isStopRequested() || (job != null && job.stopRequested.get());
    }

    private void updateJobProgress(boolean successful) {
        DocumentJob job = currentJob.get();
        if (job == null) {
            return;
        }
        if (successful) {
            job.completed.incrementAndGet();
        } else {
            job.failed.incrementAndGet();
        }
    }

    private static final class DocumentJob {
        private final String id = UUID.randomUUID().toString();
        private final String ownerId;
        private final int total;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile String state = "QUEUED";
        private volatile String message = "Ожидание запуска";

        private DocumentJob(String ownerId, int total) {
            this.ownerId = ownerId;
            this.total = total;
        }

        private boolean isRunning() {
            return "QUEUED".equals(state) || "RUNNING".equals(state) || "STOPPING".equals(state);
        }
    }

    private static final class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        private ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        public String getName() { return name; }
        public String getOriginalFilename() { return originalFilename; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File dest) throws IOException {
            try (FileOutputStream output = new FileOutputStream(dest)) {
                output.write(bytes);
            }
        }
    }
}
