package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.IndexingState;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repository.SiteRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Формирует единый наглядный статус готовности индекса всех источников. */
@Service
@RequiredArgsConstructor
public class IndexingStatusService {

    private final IndexingState indexingState;
    private final SiteRepository siteRepository;

    public Map<String, Object> getStatus() {
        List<Site> sites = siteRepository.findAll();
        long indexed = sites.stream().filter(site -> site.getStatus() == Status.INDEXED).count();
        long failed = sites.stream().filter(site -> site.getStatus() == Status.FAILED).count();
        long stopped = sites.stream().filter(site -> site.getStatus() == Status.STOPPED).count();
        long indexing = sites.stream().filter(site -> site.getStatus() == Status.INDEXING).count();
        boolean ready = !indexingState.isIndexingInProgress()
                && !sites.isEmpty()
                && indexed == sites.size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", true);
        result.put("ready", ready);
        result.put("inProgress", indexingState.isIndexingInProgress());
        result.put("stopRequested", indexingState.isStopRequested());
        result.put("activeTasks", indexingState.getActiveTasks());
        result.put("operation", indexingState.getCurrentOperation());
        result.put("totalItems", indexingState.getTotalItems());
        result.put("completedItems", indexingState.getCompletedItems());
        result.put("failedItems", indexingState.getFailedItems());
        result.put("sources", sites.size());
        result.put("indexedSources", indexed);
        result.put("indexingSources", indexing);
        result.put("failedSources", failed);
        result.put("stoppedSources", stopped);
        result.put("message", ready
                ? "Все источники полностью проиндексированы"
                : buildMessage(sites.size(), indexed, indexing, failed, stopped));
        return result;
    }

    private String buildMessage(long total, long indexed, long indexing, long failed, long stopped) {
        if (total == 0) {
            return "Источники ещё не добавлены";
        }
        if (indexingState.isIndexingInProgress() || indexing > 0) {
            return "Индексация выполняется: готово источников " + indexed + " из " + total;
        }
        if (failed > 0) {
            return "Индексация завершилась с ошибками: " + failed;
        }
        if (stopped > 0) {
            return "Индексация остановлена; требуется повторный запуск";
        }
        return "Проиндексировано источников " + indexed + " из " + total;
    }
}
