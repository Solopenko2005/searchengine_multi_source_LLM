package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Site;
import searchengine.repository.SiteRepository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SourceDeletionService {
    private final SiteRepository siteRepository;
    private final JdbcTemplate jdbcTemplate;
    private final IndexingJobService indexingJobService;
    private final CurrentUserService currentUserService;

    @Transactional
    public Map<String, Object> delete(List<Integer> ids) {
        if (!currentUserService.isAdmin()) throw new SecurityException("Требуются права администратора");
        LinkedHashSet<Integer> unique = new LinkedHashSet<>(ids == null ? List.of() : ids);
        unique.removeIf(id -> id == null || id <= 0);
        if (unique.isEmpty()) throw new IllegalArgumentException("Не выбраны источники для удаления");

        int deleted = 0;
        for (Integer id : unique) {
            Site site = siteRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Источник #" + id + " не найден"));
            if (indexingJobService.hasActiveSource(site.getUrl())) {
                throw new IllegalStateException("Сначала остановите индексацию источника «" + site.getName() + "»");
            }
            deleteSite(id);
            deleted++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", true);
        result.put("deleted", deleted);
        result.put("message", deleted == 1 ? "Источник удалён" : "Удалено источников: " + deleted);
        return result;
    }

    private void deleteSite(int siteId) {
        jdbcTemplate.update("DELETE FROM topic_lemma WHERE topic_id IN (SELECT id FROM topic WHERE site_id = ?)", siteId);
        jdbcTemplate.update("DELETE FROM topic WHERE site_id = ?", siteId);
        jdbcTemplate.update("DELETE FROM search_index WHERE page_id IN (SELECT id FROM page WHERE site_id = ?)", siteId);
        jdbcTemplate.update("DELETE FROM page WHERE site_id = ?", siteId);
        jdbcTemplate.update("DELETE FROM lemma WHERE site_id = ?", siteId);
        jdbcTemplate.update("DELETE FROM site WHERE id = ?", siteId);
    }
}
