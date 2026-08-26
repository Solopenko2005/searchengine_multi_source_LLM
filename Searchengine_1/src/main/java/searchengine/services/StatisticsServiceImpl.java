package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.IndexingState;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.repository.SiteRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.TopicRepository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
@RequiredArgsConstructor
@Service
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final TopicRepository topicRepository;
    private final IndexingState indexingState;
    private final CurrentUserService currentUserService;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics totalStatistics = new TotalStatistics();
        var accessibleSites = siteRepository.findAll().stream()
                .filter(currentUserService::canAccess)
                .collect(Collectors.toList());
        totalStatistics.setSites(accessibleSites.size());
        totalStatistics.setPages(accessibleSites.stream().mapToInt(pageRepository::countBySite).sum());
        totalStatistics.setLemmas(accessibleSites.stream().mapToInt(lemmaRepository::countBySite).sum());
        // реальное состояние: идёт ли индексация прямо сейчас (сайты или документы)
        totalStatistics.setIndexing(indexingState.isIndexingInProgress());

        List<DetailedStatisticsItem> detailed = accessibleSites.stream()
                .map(site -> {
                    DetailedStatisticsItem item = new DetailedStatisticsItem();
                    item.setUrl(site.getUrl());
                    item.setName(site.getName());
                    item.setStatus(site.getStatus().toString());
                    item.setStatusTime(site.getStatusTime() != null
                            ? site.getStatusTime().toEpochSecond(ZoneOffset.UTC) * 1000
                            : 0);
                    item.setError(site.getLastError());
                    item.setPages(pageRepository.countBySite(site));
                    item.setLemmas(lemmaRepository.countBySite(site));
                    item.setTopics(topicRepository.countBySiteId(site.getId()));
                    item.setSourceType(site.getSourceType() != null ? site.getSourceType().name() : "WEBSITE");
                    return item;
                })
                .collect(Collectors.toList());

        StatisticsData statisticsData = new StatisticsData();
        statisticsData.setTotal(totalStatistics);
        statisticsData.setDetailed(detailed);

        StatisticsResponse response = new StatisticsResponse();
        response.setResult(true);
        response.setStatistics(statisticsData);

        return response;
    }
}
