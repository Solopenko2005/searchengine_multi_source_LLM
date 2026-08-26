package searchengine.services.scientific;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.dto.scientific.ScientificArticleDto;
import searchengine.model.IndexingJob;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.model.Status;
import searchengine.model.Topic;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;
import searchengine.services.CurrentUserService;
import searchengine.services.DatabaseService;
import searchengine.services.IndexingJobService;
import searchengine.services.Lemmatizer;
import searchengine.services.PageProcessor;
import searchengine.services.TopicExtractorService;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
public class ScientificArticleIndexingService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final DatabaseService databaseService;
    private final Lemmatizer lemmatizer;
    private final TopicExtractorService topicExtractorService;
    private final TopicRepository topicRepository;
    private final PageProcessor pageProcessor;
    private final CurrentUserService currentUserService;
    private final IndexingJobService indexingJobService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public Map<String, Object> submit(ScientificArticleDto article) {
        validate(article);
        if (indexingJobService.hasActiveSource(article.getUrl())) {
            return Map.of("result", false, "error", "Эта статья уже добавляется");
        }
        String ownerId = currentUserService.getUserId();
        String title = truncate(article.getTitle(), 500);
        IndexingJob job = indexingJobService.create(ownerId, SourceType.SCIENTIFIC_ARTICLE,
                title, article.getUrl(), 1);
        indexingJobService.attachFuture(job.getId(), executor.submit(
                () -> index(job.getId(), ownerId, article)));
        return new LinkedHashMap<>(Map.of("result", true, "accepted", true,
                "jobId", job.getId(), "message", "Статья добавлена в очередь индексации"));
    }

    private void index(String jobId, String ownerId, ScientificArticleDto article) {
        Site site = siteRepository.findByUrl(article.getUrl()).orElseGet(Site::new);
        try {
            site.setUrl(article.getUrl());
            site.setName(truncate(article.getTitle(), 255));
            site.setSourceType(SourceType.SCIENTIFIC_ARTICLE);
            site.setStatus(Status.INDEXING);
            site.setStatusTime(LocalDateTime.now());
            site.setLastError(null);
            site.setOwnerId(ownerId);
            site = siteRepository.save(site);
            indexingJobService.markRunning(jobId, site.getId(), "Сохранение метаданных статьи");

            pageRepository.findBySiteAndPath(site.getId(), "/").ifPresent(pageProcessor::deletePageInfo);
            String html = articleHtml(article);
            Page page = new Page();
            page.setSite(site);
            page.setPath("/");
            page.setCode(200);
            page.setContent(html);
            page.setOriginalFileName(null);
            page.setOwnerId(ownerId);
            page.setTopicCount(0);
            databaseService.savePage(page);

            indexingJobService.updateStage(jobId, "Создание поискового индекса", 45);
            databaseService.savePageSearchIndex(page, site,
                    lemmatizer.extractLemmasWithRank(org.jsoup.Jsoup.parse(html).text()));
            indexingJobService.recordProcessed(jobId, true);

            indexingJobService.updateStage(jobId, "Определение тематик", 85);
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
            site.setStatus(Status.INDEXED);
            indexingJobService.complete(jobId);
        } catch (Exception exception) {
            site.setStatus(Status.FAILED);
            site.setLastError(exception.getMessage());
            indexingJobService.failed(jobId, exception.getMessage());
        } finally {
            site.setStatusTime(LocalDateTime.now());
            if (site.getUrl() != null) siteRepository.save(site);
        }
    }

    private String articleHtml(ScientificArticleDto article) {
        String authors = article.getAuthors() == null ? "" : String.join(", ", article.getAuthors());
        return "<html><head><title>" + escape(article.getTitle()) + "</title></head><body>"
                + "<h1>" + escape(article.getTitle()) + "</h1>"
                + "<p><strong>Авторы:</strong> " + escape(authors) + "</p>"
                + "<p><strong>Год:</strong> " + (article.getYear() == null ? "" : article.getYear()) + "</p>"
                + "<p><strong>Издание:</strong> " + escape(article.getVenue()) + "</p>"
                + "<p><strong>Каталог:</strong> " + escape(article.getProvider()) + "</p>"
                + "<h2>Аннотация</h2><p>" + escape(article.getAbstractText()) + "</p>"
                + "<p><a href=\"" + escape(article.getUrl()) + "\">Оригинал публикации</a></p>"
                + "</body></html>";
    }

    private void validate(ScientificArticleDto article) {
        if (article == null || article.getTitle() == null || article.getTitle().isBlank()
                || article.getUrl() == null || article.getUrl().isBlank()) {
            throw new IllegalArgumentException("У статьи отсутствует название или ссылка");
        }
        if (!article.getUrl().matches("(?i)^https?://.+")) {
            throw new IllegalArgumentException("Некорректная ссылка статьи");
        }
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String truncate(String value, int length) {
        if (value == null) return "";
        return value.length() <= length ? value : value.substring(0, length);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
