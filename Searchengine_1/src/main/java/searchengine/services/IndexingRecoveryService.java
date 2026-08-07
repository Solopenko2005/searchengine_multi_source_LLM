package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import searchengine.model.*;
import searchengine.repository.IndexingJobRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/** Восстанавливает на панели источники, созданные до появления сохраняемых заданий. */
@Service
@RequiredArgsConstructor
public class IndexingRecoveryService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexingJobRepository jobRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverLegacySources() {
        for (Site site : siteRepository.findAll()) {
            if (jobRepository.existsBySiteId(site.getId())) continue;
            int pages = pageRepository.countBySite(site);
            if (site.getStatus() == Status.INDEXING) {
                site.setStatus(Status.STOPPED);
                site.setLastError("Индексация была прервана перезапуском приложения");
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            }

            LocalDateTime now = LocalDateTime.now();
            IndexingJob job = new IndexingJob();
            job.setId(UUID.randomUUID().toString());
            job.setOwnerId("system");
            job.setSourceType(site.getSourceType() == null ? SourceType.WEBSITE : site.getSourceType());
            job.setSourceName(site.getName());
            job.setSourceUrl(site.getUrl());
            job.setSiteId(site.getId());
            job.setState(toJobState(site.getStatus()));
            job.setStage(site.getStatus() == Status.INDEXED ? "Готово"
                    : (pages > 0 ? "Доступен частично" : "Остановлено"));
            job.setProgressPercent(site.getStatus() == Status.INDEXED ? 100 : 0);
            job.setDiscoveredItems(pages);
            job.setProcessedItems(pages);
            job.setSearchableItems(pages);
            job.setFailedItems(0);
            job.setErrorMessage(site.getLastError());
            job.setCreatedAt(site.getStatusTime() == null ? now : site.getStatusTime());
            job.setFinishedAt(now);
            job.setUpdatedAt(now);
            jobRepository.save(job);
        }
    }

    private IndexingJobState toJobState(Status status) {
        if (status == Status.INDEXED) return IndexingJobState.COMPLETED;
        if (status == Status.FAILED) return IndexingJobState.FAILED;
        return IndexingJobState.STOPPED;
    }
}
