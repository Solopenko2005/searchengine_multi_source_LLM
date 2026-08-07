package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.IndexingState;
import searchengine.model.*;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

    @PersistenceContext
    private final EntityManager entityManager;
    private final IndexingState indexingState;
    private final JdbcTemplate jdbcTemplate;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;

    /**
     * Очищает данные предыдущей индексации перед полным перезапуском обхода сайтов.
     * <p>
     * Важно: удаляются только источники типа {@link searchengine.model.SourceType#WEBSITE}
     * (сайты из конфигурации/добавленные вручную) и связанные с ними страницы, леммы и индексы.
     * Источники типа {@link searchengine.model.SourceType#DOCUMENT} (загруженные DOCX/PDF)
     * не затрагиваются, чтобы полная переиндексация сайтов не удаляла ранее
     * проиндексированные документы.
     */
    @Transactional
    public void truncateAllTables() {
        if (!indexingState.isStopRequested()) {
            jdbcTemplate.update("DELETE FROM search_index WHERE page_id IN (" +
                    "SELECT id FROM page WHERE site_id IN (SELECT id FROM site WHERE source_type = 'WEBSITE'))");
            jdbcTemplate.update("DELETE FROM topic WHERE site_id IN (" +
                    "SELECT id FROM site WHERE source_type = 'WEBSITE')");
            jdbcTemplate.update("DELETE FROM page WHERE site_id IN (" +
                    "SELECT id FROM site WHERE source_type = 'WEBSITE')");
            jdbcTemplate.update("DELETE FROM lemma WHERE site_id IN (" +
                    "SELECT id FROM site WHERE source_type = 'WEBSITE')");
            jdbcTemplate.update("DELETE FROM site WHERE source_type = 'WEBSITE'");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveSite(Site site) {
        try {
            siteRepository.save(site);
            logger.info("Сохранен сайт: {}", site.getUrl());
        } catch (DataAccessException e) {
            logger.error("Ошибка доступа к данным при сохранении сайта '{}': {}", site.getUrl(), e.getMessage(), e);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class, timeout = 30)
    public void savePage(Page page) {
        if (page.getId() == null) {
            entityManager.persist(page);
        } else {
            entityManager.merge(page);
        }
        entityManager.flush();
    }

    @Transactional
    public Lemma saveLemma(String lemmaText, Site site) {
        // PostgreSQL upsert делает параллельную индексацию страниц одного сайта безопасной:
        // два потока больше не пытаются одновременно создать одну и ту же лемму.
        lemmaRepository.upsertLemma(lemmaText, site.getId());
        return lemmaRepository.findByLemmaAndSiteId(lemmaText, site.getId())
                .orElseThrow(() -> new IllegalStateException("Не удалось сохранить лемму: " + lemmaText));
    }

    @Transactional
    public void saveSearchIndex(SearchIndex searchIndex) {
        try {
            indexRepository.save(searchIndex);
            logger.debug("Сохранен SearchIndex для страницы {} и леммы {}",
                    searchIndex.getPage().getId(), searchIndex.getLemma().getId());
        } catch (DataAccessException e) {
            logger.error("Ошибка доступа к данным при сохранении SearchIndex: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Writes all lemmas and search ranks for a page in two JDBC batches instead of
     * issuing several SQL statements for every individual word.
     */
    @Transactional(rollbackFor = Exception.class)
    public int savePageSearchIndex(Page page, Site site, Map<String, Integer> lemmaRanks) {
        if (page == null || page.getId() == null || site == null || lemmaRanks == null || lemmaRanks.isEmpty()) {
            return 0;
        }

        Map<String, Integer> validRanks = new LinkedHashMap<>();
        lemmaRanks.forEach((lemma, rank) -> {
            if (lemma != null && !lemma.isBlank() && lemma.length() <= 255 && rank != null) {
                validRanks.put(lemma, rank);
            }
        });
        if (validRanks.isEmpty()) return 0;

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(validRanks.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        jdbcTemplate.batchUpdate(
                "INSERT INTO lemma (lemma, site_id, frequency) VALUES (?, ?, 1) " +
                        "ON CONFLICT (lemma, site_id) DO UPDATE SET frequency = lemma.frequency + 1",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        statement.setString(1, entries.get(index).getKey());
                        statement.setInt(2, site.getId());
                    }

                    @Override
                    public int getBatchSize() {
                        return entries.size();
                    }
                });

        jdbcTemplate.batchUpdate(
                "INSERT INTO search_index (page_id, lemma_id, ranking) " +
                        "SELECT ?, id, ? FROM lemma WHERE site_id = ? AND lemma = ?",
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        Map.Entry<String, Integer> entry = entries.get(index);
                        statement.setInt(1, page.getId());
                        statement.setFloat(2, entry.getValue().floatValue());
                        statement.setInt(3, site.getId());
                        statement.setString(4, entry.getKey());
                    }

                    @Override
                    public int getBatchSize() {
                        return entries.size();
                    }
                });
        return entries.size();
    }
}
