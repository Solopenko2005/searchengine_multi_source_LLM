package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
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
import java.net.URI;
import java.net.URISyntaxException;
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
    private ExecutorService siteCoordinator;
    private static final int MAX_RETRIES = 3;
    private static final int TIMEOUT = 10000;
    private static final int MAX_SITEMAP_FILES = 24;

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
            String trimmedUrl = normalizeUrl(url.trim());
            if (trimmedUrl == null || !isValidUrl(trimmedUrl)) {
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
        ensureSiteCoordinatorAvailable();
        String ownerId = currentOwnerId();
        IndexingJob job = indexingJobService.create(ownerId, SourceType.WEBSITE, name, url, 1);
        indexingState.taskStarted();
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> crawlSite(url, name, job.getId(), ownerId), siteCoordinator)
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
    private void crawlSite(String url, String name, String jobId, String ownerId) {
        Site site = getOrCreateSite(url, name, ownerId);
        try {
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            databaseService.saveSite(site);
            indexingJobService.markRunning(jobId, site.getId(), "Обход страниц");

            if (!indexingJobService.isStopRequested(jobId)) {
                // синхронный запуск: метод вернётся только когда обход сайта завершится
                Set<String> visitedUrls = ConcurrentHashMap.newKeySet();
                pool.invoke(new SiteSeedTask(site, site.getUrl(), jobId, visitedUrls));
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
        return getOrCreateSite(url, name, currentOwnerId());
    }

    private Site getOrCreateSite(String url, String name, String ownerId) {
        Site site = siteRepository.findByUrl(url)
                .orElseGet(() -> {
                    Site newSite = new Site();
                    newSite.setUrl(url);
                    newSite.setName(name);
                    newSite.setSourceType(SourceType.WEBSITE);
                    newSite.setStatus(Status.INDEXING);
                    newSite.setStatusTime(LocalDateTime.now());
                    return newSite;
                });
        if (site.getOwnerId() == null || site.getOwnerId().isBlank()) site.setOwnerId(ownerId);
        return site;
    }

    private String currentOwnerId() {
        try { return currentUserService.getUserId(); }
        catch (IllegalStateException ignored) { return "system"; }
    }

    /**
     * Гарантирует наличие рабочего пула потоков для фоновых задач индексации.
     */
    private synchronized void ensurePoolAvailable() {
        if (pool == null || pool.isShutdown() || pool.isTerminated()) {
            int configured = Math.max(4, indexingSettings.getCrawlParallelism());
            int parallelism = Math.min(32, Math.max(configured, Runtime.getRuntime().availableProcessors() * 2));
            pool = new ForkJoinPool(parallelism);
        }
    }

    private synchronized void ensureSiteCoordinatorAvailable() {
        if (siteCoordinator == null || siteCoordinator.isShutdown() || siteCoordinator.isTerminated()) {
            int parallelism = Math.max(2, Math.min(12, indexingSettings.getSiteParallelism()));
            siteCoordinator = Executors.newFixedThreadPool(parallelism);
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
                            newSite.setOwnerId(currentOwnerId());
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

                long delay = Math.max(0, indexingSettings.getRequestDelayMillis());
                if (delay > 0) Thread.sleep(delay);
                long startTime = System.currentTimeMillis();
                Connection connection = Jsoup.connect(url)
                        .userAgent(indexingSettings.getUserAgent())
                        .timeout(TIMEOUT)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true);
                if (indexingSettings.getReferrer() != null && !indexingSettings.getReferrer().isBlank()) {
                    connection.referrer(indexingSettings.getReferrer());
                }
                Connection.Response response = connection.execute();

                logger.info("Запрос к {} выполнен за {} мс", url, System.currentTimeMillis() - startTime);

                if (response.statusCode() >= 400) {
                    logger.warn("HTTP-ошибка {}: {}", response.statusCode(), url);
                    return null;
                }

                String contentType = response.contentType();
                if (contentType == null || !(contentType.contains("html")
                        || contentType.contains("xml") || contentType.startsWith("text/"))) {
                    logger.debug("Пропущен неподдерживаемый тип {}: {}", contentType, url);
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
            String normalized = normalizeUrl(url);
            this.url = normalized == null ? url : normalized;
            this.depth = depth;
            this.jobId = jobId;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected Void compute() {
            try {
                int maxPages = Math.max(1, indexingSettings.getMaxPagesPerSite());
                if (indexingJobService.isStopRequested(jobId) || visitedUrls.size() >= maxPages || !visitedUrls.add(url)) {
                    return null;
                }
                if (visitedUrls.size() > maxPages) {
                    visitedUrls.remove(url);
                    logger.info("Для {} достигнут безопасный лимит {} страниц", site.getUrl(),
                            maxPages);
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

                if (depth < Math.max(1, indexingSettings.getMaxDepth()) && !indexingJobService.isStopRequested(jobId)) {
                    Elements links = document.select("a[href]");
                    List<SiteIndexingTask> subTasks = links.stream()
                            .map(link -> link.absUrl("href"))
                            .map(SiteIndexingService.this::normalizeUrl)
                            .filter(Objects::nonNull)
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
            return belongsToSite(site.getUrl(), url) &&
                    !visitedUrls.contains(url) &&
                    isIndexablePageUrl(url);
        }
    }

    private class SiteSeedTask extends RecursiveAction {
        private final Site site;
        private final String rootUrl;
        private final String jobId;
        private final Set<String> visitedUrls;

        private SiteSeedTask(Site site, String rootUrl, String jobId, Set<String> visitedUrls) {
            this.site = site;
            this.rootUrl = rootUrl;
            this.jobId = jobId;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected void compute() {
            SiteIndexingTask root = new SiteIndexingTask(site, rootUrl, 0, jobId, visitedUrls);
            RecursiveAction sitemap = new RecursiveAction() {
                @Override
                protected void compute() {
                    List<SiteIndexingTask> tasks = discoverSitemapUrls(rootUrl, jobId).stream()
                            .map(url -> new SiteIndexingTask(site, url, 1, jobId, visitedUrls))
                            .collect(Collectors.toList());
                    invokeAll(tasks);
                }
            };
            invokeAll(root, sitemap);
        }
    }

    private List<String> discoverSitemapUrls(String rootUrl, String jobId) {
        LinkedHashSet<String> pages = new LinkedHashSet<>();
        ArrayDeque<String> sitemapQueue = new ArrayDeque<>();
        Set<String> visitedSitemaps = new HashSet<>();
        int pageLimit = Math.max(1, indexingSettings.getMaxPagesPerSite());
        try {
            URI root = new URI(rootUrl);
            String origin = new URI(root.getScheme(), null, root.getHost(), root.getPort(), "/", null, null).toString();
            sitemapQueue.add(origin + "sitemap.xml");
        } catch (URISyntaxException ignored) {
            return List.of();
        }

        while (!sitemapQueue.isEmpty() && visitedSitemaps.size() < MAX_SITEMAP_FILES
                && pages.size() < pageLimit && !indexingJobService.isStopRequested(jobId)) {
            String sitemapUrl = sitemapQueue.removeFirst();
            if (!visitedSitemaps.add(sitemapUrl)) continue;
            try {
                Connection.Response response = Jsoup.connect(sitemapUrl)
                        .userAgent(indexingSettings.getUserAgent())
                        .timeout(TIMEOUT)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute();
                if (response.statusCode() >= 400) continue;
                Document xml = Jsoup.parse(response.body(), response.url().toString(), Parser.xmlParser());
                for (org.jsoup.nodes.Element loc : xml.select("loc")) {
                    String normalized = normalizeUrl(loc.text());
                    if (normalized == null || !belongsToSite(rootUrl, normalized)) continue;
                    String lower = normalized.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".xml") || lower.endsWith(".xml.gz")) {
                        if (visitedSitemaps.size() + sitemapQueue.size() < MAX_SITEMAP_FILES) sitemapQueue.addLast(normalized);
                    } else if (isIndexablePageUrl(normalized)) {
                        pages.add(normalized);
                        if (pages.size() >= pageLimit) break;
                    }
                }
            } catch (Exception exception) {
                logger.debug("Карта сайта {} недоступна: {}", sitemapUrl, exception.getMessage());
            }
        }
        logger.info("Для {} найдено {} URL через sitemap", rootUrl, pages.size());
        return new ArrayList<>(pages);
    }

    private boolean isIndexablePageUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return !lower.matches(".*\\.(jpg|jpeg|png|gif|svg|webp|pdf|doc|docx|xls|xlsx|zip|rar|7z|mp3|mp4|xml|gz)(\\?.*)?$")
                && !lower.matches(".*(/login|/logout|/signin|/signup|/admin)(/.*)?$");
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
        page.setPath(pathFromUrl(url));
        page.setCode(document.connection().response().statusCode());
        page.setContent(document.outerHtml());
        page.setTopicCount(0);
        return page;
    }

    /** Adds one URL as an independent source without recursively crawling its domain. */
    public synchronized ResponseEntity<Map<String, Object>> addPage(String url, String name) {
        ensureSiteCoordinatorAvailable();
        String normalized = normalizeUrl(url);
        if (normalized == null || !isValidUrl(normalized)) {
            return ResponseEntity.badRequest().body(Map.of("result", false, "error", "Некорректный URL"));
        }
        if (indexingJobService.hasActiveSource(normalized)) {
            return ResponseEntity.badRequest().body(Map.of("result", false,
                    "error", "Этот источник уже индексируется"));
        }
        String sourceName = name == null || name.isBlank() ? normalized : name.trim();
        String ownerId = currentOwnerId();
        IndexingJob job = indexingJobService.create(ownerId, SourceType.WEBSITE,
                sourceName, normalized, 1);
        indexingState.taskStarted();
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> indexSingleSource(normalized, sourceName, job.getId(), ownerId), siteCoordinator)
                .whenComplete((ignored, error) -> indexingState.taskFinished());
        indexingJobService.attachFuture(job.getId(), future);
        return ResponseEntity.accepted().body(Map.of("result", true, "jobId", job.getId(),
                "message", "Страница принята и добавлена в очередь"));
    }

    private void indexSingleSource(String url, String name, String jobId, String ownerId) {
        Site site = getOrCreateSite(url, name, ownerId);
        try {
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            databaseService.saveSite(site);
            indexingJobService.markRunning(jobId, site.getId(), "Загрузка страницы");
            Document document = fetchDocumentWithRetries(url, jobId);
            if (document == null) throw new IOException("Страница не содержит доступного HTML-текста");
            savePageAndLemmas(site, url, document, jobId);
            indexingJobService.recordProcessed(jobId, true);
            indexingJobService.complete(jobId);
            site.setStatus(Status.INDEXED);
            site.setLastError(null);
        } catch (Exception exception) {
            indexingJobService.failed(jobId, exception.getMessage());
            site.setStatus(indexingJobService.isStopRequested(jobId) ? Status.STOPPED : Status.FAILED);
            site.setLastError(exception.getMessage());
            logger.warn("Не удалось проиндексировать отдельную страницу {}: {}", url, exception.getMessage());
        } finally {
            site.setStatusTime(LocalDateTime.now());
            databaseService.saveSite(site);
        }
    }

    /** Normalizes redirects, fragments and common tracking parameters before de-duplication. */
    private String normalizeUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = stripTrackingParameters(uri.getRawQuery());
            return new URI(scheme, null, host.toLowerCase(Locale.ROOT), uri.getPort(), path,
                    query, null).toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private boolean belongsToSite(String rootUrl, String candidateUrl) {
        try {
            String rootHost = normalizedHost(new URI(rootUrl).getHost());
            String candidateHost = normalizedHost(new URI(candidateUrl).getHost());
            return rootHost != null && rootHost.equals(candidateHost);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private String normalizedHost(String host) {
        if (host == null) return null;
        String result = host.toLowerCase(Locale.ROOT);
        return result.startsWith("www.") ? result.substring(4) : result;
    }

    private String stripTrackingParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;
        String cleaned = Arrays.stream(rawQuery.split("&"))
                .filter(parameter -> {
                    String key = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    return !key.startsWith("utm_") && !key.equals("fbclid")
                            && !key.equals("gclid") && !key.equals("yclid");
                })
                .collect(Collectors.joining("&"));
        return cleaned.isBlank() ? null : cleaned;
    }

    private String pathFromUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) path = "/";
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                path += "?" + uri.getRawQuery();
            }
            return path.length() <= 2048 ? path : path.substring(0, 2048);
        } catch (URISyntaxException exception) {
            return url.length() <= 2048 ? url : url.substring(0, 2048);
        }
    }

    @PreDestroy
    void shutdownExecutors() {
        if (siteCoordinator != null) siteCoordinator.shutdownNow();
        if (pool != null) pool.shutdownNow();
    }

}
