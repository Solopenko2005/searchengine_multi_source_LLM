package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import searchengine.model.*;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Параллельная, независимо останавливаемая индексация PDF и DOCX. */
@Service
@RequiredArgsConstructor
public class DocumentIndexingService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentIndexingService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("docx", "pdf");

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final DatabaseService databaseService;
    private final Lemmatizer lemmatizer;
    private final TopicExtractorService topicExtractorService;
    private final TopicRepository topicRepository;
    private final PageProcessor pageProcessor;
    private final CurrentUserService currentUserService;
    private final IndexingJobService indexingJobService;

    @Value("${indexing-settings.documents.enabled:true}")
    private boolean documentIndexingEnabled;

    @Value("${indexing-settings.documents.max-file-size-bytes:52428800}")
    private long maxFileSizeBytes;

    private final ExecutorService documentExecutor = Executors.newFixedThreadPool(4);
    private final ThreadLocal<String> currentJobId = new ThreadLocal<>();

    public Map<String, Object> submitDocuments(MultipartFile[] files) {
        if (!documentIndexingEnabled) return error("Индексация документов отключена");
        if (files == null || files.length == 0) return error("Не выбран ни один файл");

        List<StoredMultipartFile> snapshots = new ArrayList<>();
        try {
            Path uploadDirectory = Path.of(System.getProperty("java.io.tmpdir"),
                    "searchengine-indexing-uploads");
            Files.createDirectories(uploadDirectory);
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    snapshots.forEach(StoredMultipartFile::cleanup);
                    return error("Один из загруженных файлов пуст");
                }
                String extension = extractExtension(file.getOriginalFilename());
                if (!SUPPORTED_EXTENSIONS.contains(extension)) {
                    snapshots.forEach(StoredMultipartFile::cleanup);
                    return error("Поддерживаются только форматы DOCX и PDF");
                }
                if (file.getSize() > maxFileSizeBytes) {
                    snapshots.forEach(StoredMultipartFile::cleanup);
                    return error("Файл " + file.getOriginalFilename() + " превышает допустимый размер");
                }
                Path storedFile = Files.createTempFile(uploadDirectory, "document-", "." + extension);
                file.transferTo(storedFile.toFile());
                snapshots.add(new StoredMultipartFile(file.getName(), file.getOriginalFilename(),
                        file.getContentType(), storedFile));
            }
        } catch (IOException e) {
            snapshots.forEach(StoredMultipartFile::cleanup);
            return error("Не удалось принять загруженные файлы: " + e.getMessage());
        }

        String ownerId = currentUserService.getUserId();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> sourceUrls = new HashSet<>();
        for (StoredMultipartFile file : snapshots) {
            String sourceUrl = documentSourceUrl(ownerId, safeFileName(file.getOriginalFilename()));
            if (!sourceUrls.add(sourceUrl) || indexingJobService.hasActiveSource(sourceUrl)) {
                snapshots.forEach(StoredMultipartFile::cleanup);
                return error("Документ " + file.getOriginalFilename() + " уже индексируется");
            }
        }
        List<String> jobIds = new ArrayList<>();
        for (StoredMultipartFile file : snapshots) {
            String name = safeFileName(file.getOriginalFilename());
            String sourceUrl = documentSourceUrl(ownerId, name);
            IndexingJob job = indexingJobService.create(ownerId, SourceType.DOCUMENT, name, sourceUrl, 1);
            jobIds.add(job.getId());
            indexingJobService.attachFuture(job.getId(), documentExecutor.submit(
                    () -> runSingleJob(job.getId(), ownerId, file, authentication)));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", true);
        response.put("accepted", true);
        response.put("jobIds", jobIds);
        response.put("total", jobIds.size());
        response.put("message", "Документы приняты и индексируются параллельно");
        return response;
    }

    private void runSingleJob(String jobId, String ownerId, StoredMultipartFile file,
                              Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        currentJobId.set(jobId);
        try {
            indexingJobService.markRunning(jobId, null, "Извлечение текста");
            Map<String, Object> result = indexSingleDocument(file, ownerId, jobId);
            if (shouldStop(jobId)) indexingJobService.stopped(jobId);
            else if (Boolean.TRUE.equals(result.get("result"))) indexingJobService.complete(jobId);
            else indexingJobService.failed(jobId, String.valueOf(result.get("error")));
        } catch (CancellationException e) {
            indexingJobService.stopped(jobId);
        } catch (Exception e) {
            indexingJobService.failed(jobId, e.getMessage());
            logger.error("Ошибка индексации документа {}", file.getOriginalFilename(), e);
        } finally {
            file.cleanup();
            currentJobId.remove();
            SecurityContextHolder.clearContext();
        }
    }

    public Map<String, Object> stopCurrentUserJob() {
        int count = indexingJobService.requestStopAllVisible(SourceType.DOCUMENT);
        return count == 0 ? error("Активная индексация документов не найдена")
                : Map.of("result", true, "stopped", count, "message", "Остановка запрошена");
    }

    public Map<String, Object> currentUserStatus() {
        List<Map<String, Object>> documents = indexingJobService.listVisible().stream()
                .filter(job -> job.get("sourceType") == SourceType.DOCUMENT).toList();
        long active = documents.stream().filter(job -> Boolean.TRUE.equals(job.get("canStop"))).count();
        long ready = documents.stream().filter(job -> Boolean.TRUE.equals(job.get("partialSearchReady"))).count();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("result", true);
        status.put("enabled", documentIndexingEnabled);
        status.put("inProgress", active > 0);
        status.put("state", active > 0 ? "RUNNING" : "IDLE");
        status.put("ready", ready > 0);
        status.put("indexedDocuments", ready);
        status.put("total", documents.size());
        return status;
    }

    /** Синхронный совместимый метод; HTTP-загрузка использует отдельное задание на файл. */
    public Map<String, Object> indexDocuments(MultipartFile[] files) {
        if (files == null || files.length == 0) return error("Не выбрано ни одного файла");
        String ownerId = currentUserService.getUserId();
        List<Map<String, Object>> results = new ArrayList<>();
        int success = 0;
        for (MultipartFile file : files) {
            Map<String, Object> result = indexSingleDocument(file, ownerId, null);
            results.add(result);
            if (Boolean.TRUE.equals(result.get("result"))) success++;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", success > 0);
        response.put("total", files.length);
        response.put("success", success);
        response.put("failed", files.length - success);
        response.put("files", results);
        response.put("message", "Проиндексировано документов: " + success + " из " + files.length);
        return response;
    }

    private Map<String, Object> indexSingleDocument(MultipartFile file, String ownerId, String jobId) {
        String originalName = safeFileName(file == null ? null : file.getOriginalFilename());
        if (file == null || file.isEmpty()) return fileResult(originalName, false, "Файл пуст");
        String extension = extractExtension(originalName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) return fileResult(originalName, false,
                "Поддерживаются только форматы DOCX и PDF");

        try {
            checkStop(jobId);
            updateStage(jobId, "Извлечение текста", 15);
            String extractedText = extractText(file, extension);
            if (extractedText == null || extractedText.isBlank()) {
                return fileResult(originalName, false, "В документе нет текста для индексации");
            }

            checkStop(jobId);
            Site site = getOrCreateDocumentSite(ownerId, originalName);
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            siteRepository.save(site);
            if (jobId != null) indexingJobService.markRunning(jobId, site.getId(), "Сохранение документа");

            pageRepository.findBySiteAndPathAndOwnerId(site.getId(), "/document", ownerId)
                    .ifPresent(pageProcessor::deletePageInfo);
            String html = wrapAsHtml(originalName, extractedText);
            Page page = new Page();
            page.setSite(site);
            page.setPath("/document");
            page.setCode(200);
            page.setContent(html);
            page.setOriginalFileName(originalName);
            page.setOwnerId(ownerId);
            page.setTopicCount(0);
            databaseService.savePage(page);

            checkStop(jobId);
            updateStage(jobId, "Лемматизация и поисковый индекс", 45);
            indexLemmas(site, page, extractedText, jobId);
            if (jobId != null) indexingJobService.recordProcessed(jobId, true);

            checkStop(jobId);
            updateStage(jobId, "Определение тематик", 85);
            int topicsCount = extractAndSaveTopics(page, html, site);
            site.setStatus(Status.INDEXED);
            site.setStatusTime(LocalDateTime.now());
            siteRepository.save(site);

            Map<String, Object> ok = fileResult(originalName, true, "Проиндексирован");
            ok.put("pageId", page.getId());
            ok.put("topics", topicsCount);
            return ok;
        } catch (CancellationException e) {
            markDocumentSiteStopped(ownerId, originalName);
            throw e;
        } catch (Exception e) {
            markDocumentSiteFailed(ownerId, originalName, e.getMessage());
            logger.error("Ошибка при индексации документа {}: {}", originalName, e.getMessage(), e);
            return fileResult(originalName, false, "Ошибка индексации: " + e.getMessage());
        }
    }

    private Site getOrCreateDocumentSite(String ownerId, String fileName) {
        String url = documentSourceUrl(ownerId, fileName);
        Site site = siteRepository.findByUrl(url).orElseGet(() -> {
            Site newSite = new Site();
            newSite.setUrl(url);
            newSite.setName(fileName);
            newSite.setSourceType(SourceType.DOCUMENT);
            newSite.setStatus(Status.INDEXING);
            newSite.setStatusTime(LocalDateTime.now());
            newSite.setOwnerId(ownerId);
            return siteRepository.save(newSite);
        });
        if (site.getOwnerId() == null || site.getOwnerId().isBlank()) {
            site.setOwnerId(ownerId);
            siteRepository.save(site);
        }
        return site;
    }

    private String documentSourceUrl(String ownerId, String fileName) {
        String key = ownerId + ":" + fileName.toLowerCase(Locale.ROOT);
        return "local://document/" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private void markDocumentSiteStopped(String ownerId, String fileName) {
        Site site = getOrCreateDocumentSite(ownerId, fileName);
        site.setStatus(Status.STOPPED);
        site.setStatusTime(LocalDateTime.now());
        site.setLastError("Индексация остановлена пользователем");
        siteRepository.save(site);
    }

    private void markDocumentSiteFailed(String ownerId, String fileName, String error) {
        Site site = getOrCreateDocumentSite(ownerId, fileName);
        site.setStatus(Status.FAILED);
        site.setStatusTime(LocalDateTime.now());
        site.setLastError(error);
        siteRepository.save(site);
    }

    private String extractText(MultipartFile file, String extension) throws IOException {
        if ("docx".equals(extension)) {
            try (XWPFDocument document = new XWPFDocument(file.getInputStream());
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        }
        try (PDDocument document = PDDocument.load(file.getInputStream(),
                MemoryUsageSetting.setupTempFileOnly())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private void indexLemmas(Site site, Page page, String text, String jobId) {
        Map<String, Integer> lemmas = lemmatizer.extractLemmasWithRank(text);
        checkStop(jobId);
        databaseService.savePageSearchIndex(page, site, lemmas);
        updateStage(jobId, "Поисковый индекс готов", 80);
    }

    private int extractAndSaveTopics(Page page, String html, Site site) {
        try {
            topicRepository.deleteByPageId(page.getId());
            List<Topic> topics = topicExtractorService.extractTopics(page, html);
            for (Topic topic : topics) {
                topic.setPage(page);
                topic.setSite(site);
                topic.setLemmaCount(lemmatizer.extractLemmasWithRank(topic.getContent()).values().stream()
                        .mapToInt(Integer::intValue).sum());
                topicRepository.save(topic);
            }
            page.setTopicCount(topics.size());
            pageRepository.save(page);
            return topics.size();
        } catch (Exception e) {
            logger.warn("Не удалось определить тематики документа {}: {}", page.getPath(), e.getMessage());
            return 0;
        }
    }

    private void checkStop(String jobId) {
        if (jobId != null && indexingJobService.isStopRequested(jobId)) throw new CancellationException();
    }

    private boolean shouldStop(String jobId) {
        return jobId != null && indexingJobService.isStopRequested(jobId);
    }

    private void updateStage(String jobId, String stage, int progress) {
        if (jobId != null) indexingJobService.updateStage(jobId, stage, progress);
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String name) {
        if (name == null || name.isBlank()) return "document";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return (slash >= 0 ? name.substring(slash + 1) : name).trim();
    }

    private String wrapAsHtml(String title, String text) {
        return "<html><head><title>" + escapeHtml(title) + "</title></head><body>"
                + escapeHtml(text).replace("\r\n", "\n").replace("\n", "<br/>") + "</body></html>";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Map<String, Object> fileResult(String fileName, boolean ok, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileName", fileName);
        map.put("result", ok);
        map.put(ok ? "message" : "error", message);
        return map;
    }

    private Map<String, Object> error(String message) {
        return new LinkedHashMap<>(Map.of("result", false, "error", message));
    }

    @PreDestroy
    void shutdownExecutor() {
        documentExecutor.shutdownNow();
    }

    /**
     * Keeps an accepted upload on disk while it waits in the executor queue.
     * Large batches therefore do not occupy the JVM heap with duplicate byte arrays.
     */
    private static final class StoredMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final Path path;

        private StoredMultipartFile(String name, String originalFilename, String contentType, Path path) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.path = path;
        }
        public String getName() { return name; }
        public String getOriginalFilename() { return originalFilename; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() {
            try { return Files.size(path) == 0; } catch (IOException exception) { return true; }
        }
        public long getSize() {
            try { return Files.size(path); } catch (IOException exception) { return 0; }
        }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File dest) throws IOException {
            Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        void cleanup() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                logger.warn("Не удалось удалить временный файл {}: {}", path, exception.getMessage());
            }
        }
    }
}
