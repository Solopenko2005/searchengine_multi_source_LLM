package searchengine.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import searchengine.config.IndexingSettings;
import searchengine.config.IndexingState;
import searchengine.model.IndexingJob;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SiteIndexingServiceTest {
    private HttpServer server;
    private SiteIndexingService service;
    private DatabaseService databaseService;
    private IndexingJobService indexingJobService;
    private String rootUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        rootUrl = "http://127.0.0.1:" + port + "/";
        page("/", "<html><body><h1>Root page</h1><p>Enough searchable content for the root page.</p>"
                + "<a href='/one'>one</a><a href='/two'>two</a></body></html>", "text/html");
        page("/one", "<html><body><h1>First page</h1><p>Enough searchable content for page one.</p></body></html>", "text/html");
        page("/two", "<html><body><h1>Second page</h1><p>Enough searchable content for page two.</p></body></html>", "text/html");
        page("/three", "<html><body><h1>Sitemap page</h1><p>This page is discoverable only from the sitemap.</p></body></html>", "text/html");
        page("/sitemap.xml", "<?xml version='1.0'?><urlset><url><loc>" + rootUrl
                + "three</loc></url></urlset>", "application/xml");
        server.start();

        Lemmatizer lemmatizer = mock(Lemmatizer.class);
        databaseService = mock(DatabaseService.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        PageProcessor pageProcessor = mock(PageProcessor.class);
        IndexingState indexingState = mock(IndexingState.class);
        TopicExtractorService topicExtractorService = mock(TopicExtractorService.class);
        TopicRepository topicRepository = mock(TopicRepository.class);
        indexingJobService = mock(IndexingJobService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        IndexingSettings settings = new IndexingSettings();
        settings.setUserAgent("SiteIndexingServiceTest/1.0");
        settings.setRequestDelayMillis(0);
        settings.setCrawlParallelism(8);
        settings.setSiteParallelism(2);
        settings.setMaxDepth(5);
        settings.setMaxPagesPerSite(20);

        AtomicReference<Site> storedSite = new AtomicReference<>();
        when(siteRepository.findByUrl(anyString())).thenAnswer(invocation -> {
            Site site = storedSite.get();
            return site != null && invocation.getArgument(0).equals(site.getUrl())
                    ? Optional.of(site) : Optional.empty();
        });
        doAnswer(invocation -> {
            Site site = invocation.getArgument(0);
            if (site.getId() == 0) site.setId(1);
            storedSite.set(site);
            return null;
        }).when(databaseService).saveSite(any(Site.class));
        AtomicInteger pageIds = new AtomicInteger();
        doAnswer(invocation -> {
            Page indexedPage = invocation.getArgument(0);
            if (indexedPage.getId() == null) indexedPage.setId(pageIds.incrementAndGet());
            return null;
        }).when(databaseService).savePage(any(Page.class));
        when(lemmatizer.extractLemmasWithRank(anyString())).thenReturn(Map.of());
        when(topicExtractorService.extractTopics(any(Page.class), anyString())).thenReturn(List.of());
        when(currentUserService.getUserId()).thenReturn("admin@example.test");
        when(indexingJobService.hasActiveSource(anyString())).thenReturn(false);
        when(indexingJobService.isStopRequested(anyString())).thenReturn(false);
        IndexingJob job = new IndexingJob();
        job.setId("crawl-job");
        when(indexingJobService.create(anyString(), any(), anyString(), anyString(), anyInt())).thenReturn(job);

        service = new SiteIndexingService(lemmatizer, databaseService, settings, siteRepository,
                pageProcessor, indexingState, topicExtractorService, topicRepository,
                indexingJobService, currentUserService);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdownExecutors();
        if (server != null) server.stop(0);
    }

    @Test
    void crawlsLinksAndSitemapPagesInOneJob() {
        service.addSite(rootUrl, "Local test source");

        verify(indexingJobService, timeout(10_000)).complete("crawl-job");
        verify(databaseService, atLeast(4)).savePage(any(Page.class));
        verify(indexingJobService, atLeast(4)).recordProcessed(eq("crawl-job"), eq(true));
    }

    private void page(String path, String body, String contentType) {
        server.createContext(path, exchange -> respond(exchange, body, contentType));
    }

    private void respond(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
