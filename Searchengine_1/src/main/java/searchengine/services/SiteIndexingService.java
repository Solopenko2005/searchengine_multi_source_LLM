package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.IndexingSettings;
import searchengine.config.IndexingState;
import searchengine.dto.response.IndexingResponse;
import searchengine.model.*;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SiteIndexingService {

    private final Lemmatizer lemmatizer;
    private final DatabaseService databaseService;
    private final IndexingSettings indexingSettings;
    private final SiteRepository siteRepository;
    private final PageProcessor pageProcessor;
    private final IndexingState indexingState; // общий флаг индексации для всех источников
    private final TopicExtractorService topicExtractorService;
    private final TopicRepository topicRepository;
    private final IndexingJobService indexingJobService;
    private final CurrentUserService currentUserService;

    private static final Logger logger = LoggerFactory.getLogger(SiteIndexingService.class);
    private ForkJoinPool pool;
    private final ExecutorService siteCoordinator = Executors.newFixedThreadPool(4);
    private static final int MAX_RETRIES = 3;
    private static final int TIMEOUT = 10000;

    /**
     * Запускает полную индексацию всех сайтов из конфигурации indexing-settings.sites.
     * Данные документов (источники типа DOCUMENT) при этом не затрагиваются.
     */
    public synchronized ResponseEntity<Map<String, Object>> startIndexing() {
        try {
            indexingState.clearStop();
            pageProcessor.resumeIndexing();
            ensurePoolAvailable();

            List<IndexingSettings.SiteConfig> sites = indexingSettings.getSites();
            if (sites == null || sites.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "result", true,
                        "message", "Список сайтов для индексации пуст"
                ));
            }

            indexingState.beginOperation("Индексация сайтов", sites.size());

            for (IndexingSettings.SiteConfig siteConfig : sites) {
                if (!indexingJobService.hasActiveSource(siteConfig.getUrl())) {
                    launchSiteCrawl(siteConfig.getUrl(), siteConfig.getName());
                }
            }

            return ResponseEntity.ok(Map.of(
                    "result", true,
                    "message", "Индексация запущена"
            ));
        } catch (Exception e) {
            logger.error("Ошибка при запуске индексации", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "result", false,
                    "error", "Internal Server Error"
            ));
        }
    }

    /**
     * Добавляет новый источник-сайт (URL, которого может не быть в статической конфигурации)
     * и немедленно запускает его индексацию, не затрагивая данные других источников.
     */
    public synchronized ResponseEntity<Map<String, Object>> addSite(String url, String name) {
        try {
            if (url == null || url.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "URL источника не может быть пустым"
                ));
            }
            String trimmedUrl = url.trim();
            if (!isValidUrl(trimmedUrl)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "Некорректный URL"
                ));
            }

            String siteName = (name == null || name.trim().isEmpty()) ? trimmedUrl : name.trim();

            if (indexingJobService.hasActiveSource(trimmedUrl)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "Этот источник уже индексируется; его состояние видно в таблице"
                ));
            }

            indexingState.clearStop();
            ensurePoolAvailable();

            String jobId = launchSiteCrawl(trimmedUrl, siteName);

            logger.info("Добавлен источник для индексации: {}", trimmedUrl);
            return ResponseEntity.ok(Map.of(
                    "result", true,
                    "jobId", jobId,
                    "message", "Источник добавлен, индексация запущена"
            ));
        } catch (Exception e) {
            logger.error("Ошибка при добавлении источника {}: {}", url, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "result", false,
                    "error", "Ошибка при добавлении источника: " + e.getMessage()
            ));
        }
    }

    /**
     * Регистрирует единицу работы в общем состоянии и асинхронно запускает обход одного сайта.
     * Когда завершится последняя единица работы (сайт или пакет документов),
     * общий флаг «идёт индексация» будет снят автоматически.
     */
    private String launchSiteCrawl(String url, String name) {
        String ownerId;
        try {
            ownerId = currentUserService.getUserId();
        } catch (IllegalStateException ignored) {
            ownerId = "system";
        }
        IndexingJob job = indexingJobService.create(ownerId, SourceType.WEBSITE, name, url, 1);
        indexingState.taskStarted();
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> crawlSite(url, name, job.getId()), siteCoordinator)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        logger.error("Непредвиденная ошибка обхода сайта {}: {}", url, error.getMessage(), error);
                    }
                    indexingState.taskFinished();
                });
        indexingJobService.attachFuture(job.getId(), future);
        return job.getId();
    }

    /**
     * Синхронно обходит один сайт целиком и по завершении выставляет итоговый статус:
     * INDEXED — если обход завершился нормально, FAILED — если была запрошена остановка.
     */
    private void crawlSite(String url, String name, String jobId) {
        Site site = getOrCreateSite(url, name);
        try {
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            databaseService.saveSite(site);
            indexingJobService.markRunning(jobId, site.getId(), "Обход страниц");

            if (!indexingJobService.isStopRequested(jobId)) {
                // синхронный запуск: метод вернётся только когда обход сайта завершится
                pool.invoke(new SiteIndexingTask(site, site.getUrl(), 0, jobId,
                        ConcurrentHashMap.newKeySet()));
            }

            Site finalSite = siteRepository.findByUrl(url).orElse(site);
            if (indexingJobService.isStopRequested(jobId)) {
                finalSite.setStatus(Status.STOPPED);
                finalSite.setLastError("Индексация остановлена пользователем");
                indexingJobService.stopped(jobId);
                indexingState.itemFailed();
            } else {
                finalSite.setStatus(Status.INDEXED);
                finalSite.setLastError(null);
                indexingJobService.complete(jobId);
                indexingState.itemCompleted();
            }
            finalSite.setStatusTime(LocalDateTime.now());
            databaseService.saveSite(finalSite);
        } catch (Exception e) {
            indexingState.itemFailed();
            indexingJobService.failed(jobId, e.getMessage());
            handleSiteError(site, e);
        }
    }

    private Site getOrCreateSite(String url, String name) {
        return siteRepository.findByUrl(url)
                .orElseGet(() -> {
                    Site newSite = new Site();
                    newSite.setUrl(url);
                    newSite.setName(name);
                    newSite.setSourceType(SourceType.WEBSITE);
                    newSite.setStatus(Status.INDEXING);
                    newSite.setStatusTime(LocalDateTime.now());
                    return newSite;
                });
    }

    /**
     * Гарантирует наличие рабочего пула потоков для фоновых задач индексации.
     */
    private synchronized void ensurePoolAvailable() {
        if (pool == null || pool.isShutdown() || pool.isTerminated()) {
            pool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        }
    }

    private void handleSiteError(Site site, Exception e) {
        Site finalSite = siteRepository.findByUrl(site.getUrl()).orElse(site);
        finalSite.setStatus(Status.FAILED);
        finalSite.setLastError(e.getMessage());
        finalSite.setStatusTime(LocalDateTime.now());
        databaseService.saveSite(finalSite);
        logger.error("Ошибка индексации {}: {}", site.getUrl(), e.getMessage(), e);
    }

    /**
     * Останавливает индексацию сразу у всех источников (и обход сайтов, и загрузку документов).
     */
    public synchronized ResponseEntity<IndexingResponse> stopIndexing() {
        logger.info("Запрос на остановку индексации...");
        try {
            if (!indexingState.isIndexingInProgress()) {
                return ResponseEntity.badRequest().body(
                        new IndexingResponse(false, "Индексация не запущена")
                );
            }

            int stopped = indexingJobService.requestStopAllVisible();

            return ResponseEntity.ok(
                    new IndexingResponse(true, "Останавливается заданий: " + stopped)
            );
        } catch (Exception e) {
            logger.error("Ошибка при остановке индексации", e);
            return ResponseEntity.internalServerError().body(
                    new IndexingResponse(false, "Ошибка при остановке индексации")
            );
        }
    }

    /**
     * Индексация/переиндексация одной отдельной страницы уже добавленного сайта.
     */
    public ResponseEntity<Map<String, Object>> indexPage(String url) {
        try {
            if (indexingState.isIndexingInProgress()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "Индексация уже запущена, дождитесь её завершения"
                ));
            }

            if (!isValidUrl(url)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "Некорректный URL"
                ));
            }

            Site site = getSiteByUrl(url);
            if (site == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "result", false,
                        "error", "Страница не принадлежит ни одному из добавленных сайтов"
                ));
            }

            indexingState.clearStop();
            pageProcessor.resumeIndexing();
            indexingState.taskStarted();
            try {
                pageProcessor.deletePageInfoIfExists(site, url);
                pageProcessor.indexPage(site, url, 0);
                return ResponseEntity.ok(Map.of("result", true));
            } finally {
                indexingState.taskFinished();
            }

        } catch (Exception e) {
            logger.error("Ошибка индексации страницы", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "result", false,
                    "error", "Ошибка индексации: " + e.getMessage()
            ));
        }
    }

    /**
     * Находит сайт-источник, которому принадлежит указанный URL.
     * В первую очередь ищем среди сайтов, сохранённых в БД (включая добавленные вручную),
     * затем — среди статической конфигурации.
     */
    private Site getSiteByUrl(String url) {
        Optional<Site> dbMatch = siteRepository.findAll().stream()
                .filter(s -> s.getSourceType() == null || s.getSourceType() == SourceType.WEBSITE)
                .filter(s -> s.getUrl() != null && url.startsWith(s.getUrl()))
                .max(Comparator.comparingInt(s -> s.getUrl().length()));

        if (dbMatch.isPresent()) {
            return dbMatch.get();
        }

        if (indexingSettings.getSites() == null) {
            return null;
        }

        return indexingSettings.getSites().stream()
                .filter(siteConfig -> url.startsWith(siteConfig.getUrl()))
                .findFirst()
                .map(siteConfig -> siteRepository.findByUrl(siteConfig.getUrl())
                        .orElseGet(() -> {
                            Site newSite = new Site();
                            newSite.setUrl(siteConfig.getUrl());
                            newSite.setName(siteConfig.getName());
                            newSite.setSourceType(SourceType.WEBSITE);
                            newSite.setStatus(Status.INDEXING);
                            newSite.setStatusTime(LocalDateTime.now());
                            return newSite;
                        }))
                .orElse(null);
    }

    private boolean isValidUrl(String url) {
        try {
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private Document fetchDocumentWithRetries(String url, String jobId) throws IOException {
        int retries = 0;
        while (retries < MAX_RETRIES && !indexingJobService.isStopRequested(jobId)) {
            try {
                if (indexingJobService.isStopRequested(jobId)) {
                    throw new IOException("Задача прервана");
                }

                Thread.sleep(500);
                long startTime = System.currentTimeMillis();
                Connection.Response response = Jsoup.connect(url)
                        .userAgent("HeliontSearchBot")
                        .timeout(TIMEOUT)
                        .execute();

                logger.info("Запрос к {} выполнен за {} мс", url, System.currentTimeMillis() - startTime);

                if (response.statusCode() >= 400) {
                    logger.warn("HTTP-ошибка {}: {}", response.statusCode(), url);
                    return null;
                }

                return response.parse();
            } catch (SocketTimeoutException e) {
                retries++;
                logger.warn("Таймаут подключения к {}. Попытка {}/{}", url, retries, MAX_RETRIES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Задача прервана", e);
            }
        }
        if (indexingJobService.isStopRequested(jobId)) {
            return null;
        }
        throw new IOException("Не удалось загрузить страницу после " + MAX_RETRIES + " попыток: " + url);
    }

    private class SiteIndexingTask extends RecursiveTask<Void> {
        private final Site site;
        private final String url;
        private final int depth;
        private final String jobId;
        private final Set<String> visitedUrls;

        public SiteIndexingTask(Site site, String url, int depth, String jobId, Set<String> visitedUrls) {
            this.site = site;
            this.url = url;
            this.depth = depth;
            this.jobId = jobId;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected Void compute() {
            try {
                if (indexingJobService.isStopRequested(jobId) || !visitedUrls.add(url)) {
                    return null;
                }
                if (depth > 0) indexingJobService.recordDiscovered(jobId);

                Document document = fetchDocumentWithRetries(url, jobId);
                if (document == null || indexingJobService.isStopRequested(jobId)) {
                    indexingJobService.recordProcessed(jobId, false);
                    return null;
                }

                savePageAndLemmas(site, url, document, jobId);
                indexingJobService.recordProcessed(jobId, true);

                if (depth < 10 && !indexingJobService.isStopRequested(jobId)) {
                    Elements links = document.select("a[href]");
                    List<SiteIndexingTask> subTasks = links.stream()
                            .map(link -> link.absUrl("href"))
                            .filter(this::isValidUrl)
                            .map(link -> new SiteIndexingTask(site, link, depth + 1, jobId, visitedUrls))
                            .collect(Collectors.toList());

                    invokeAll(subTasks);
                }
            } catch (CancellationException e) {
                logger.warn("Задача была отменена для URL: {}", url);
            } catch (Exception e) {
                indexingJobService.recordProcessed(jobId, false);
                logger.error("Ошибка обработки {}: {}", url, e.getMessage(), e);
            }
            return null;
        }

        private boolean isValidUrl(String url) {
            return url.startsWith(site.getUrl()) &&
                    !visitedUrls.contains(url) &&
                    !url.contains("#") &&
                    !url.endsWith(".jpg") &&
                    !url.endsWith(".png") &&
                    !url.endsWith(".pdf");
        }
    }

    @Transactional(rollbackFor = Exception.class, timeout = 30)
    protected void savePageAndLemmas(Site site, String url, Document document, String jobId) {
        if (indexingJobService.isStopRequested(jobId)) {
            logger.info("Индексация прервана пользователем для URL: {}", url);
            throw new RuntimeException("Индексация прервана");
        }

        pageProcessor.deletePageInfoIfExists(site, url);
        Page page = createPage(site, url, document);
        String content = document.body().text();
        String htmlContent = document.outerHtml();

        databaseService.savePage(page);
        Map<String, Integer> lemmaMap = lemmatizer.extractLemmasWithRank(content);
        databaseService.savePageSearchIndex(page, site, lemmaMap);

        // Тематики считаются после поискового индекса: сохранённая страница уже
        // доступна поиску, даже если более дорогой LLM-анализ ещё продолжается.
        extractAndSaveTopics(page, htmlContent);

        logger.debug("Страница {} проиндексирована с {} темами", url, page.getTopics().size());
    }

    private void extractAndSaveTopics(Page page, String htmlContent) {
        try {
            topicRepository.deleteByPageId(page.getId());

            List<Topic> topics = topicExtractorService.extractTopics(page, htmlContent);

            if (!topics.isEmpty()) {
                for (Topic topic : topics) {
                    topic.setPage(page);
                    topic.setSite(page.getSite());
                    topic.setLemmaCount(calculateLemmaCount(topic.getContent()));
                    topicRepository.save(topic);
                }

                page.setTopicCount(topics.size());
                databaseService.savePage(page);

                logger.debug("Извлечено {} тем для страницы {}", topics.size(), page.getPath());
            }
        } catch (Exception e) {
            logger.error("Ошибка при извлечении тем для страницы {}: {}",
                    page.getPath(), e.getMessage());
        }
    }

    private int calculateLemmaCount(String content) {
        if (content == null || content.trim().isEmpty()) {
            return 0;
        }
        Map<String, Integer> lemmas = lemmatizer.extractLemmasWithRank(content);
        return lemmas.values().stream().mapToInt(Integer::intValue).sum();
    }

    private Page createPage(Site site, String url, Document document) {
        Page page = new Page();
        page.setSite(site);
        String path = url.replace(site.getUrl(), "");
        page.setPath(path.isEmpty() ? "/" : path);
        page.setCode(document.connection().response().statusCode());
        page.setContent(document.outerHtml());
        page.setTopicCount(0);
        return page;
    }

    @PreDestroy
    void shutdownExecutors() {
        siteCoordinator.shutdownNow();
        if (pool != null) pool.shutdownNow();
    }

}
