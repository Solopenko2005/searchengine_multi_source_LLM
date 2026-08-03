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

    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public void savePage(Page page) {
        if (indexingState.isStopRequested()) {
            throw new RuntimeException("Индексация прервана");
        }
        entityManager.persist(page);
        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    public Lemma saveLemma(String lemmaText, Site site) {
        Optional<Lemma> optionalLemma = lemmaRepository.findByLemmaAndSite(lemmaText, site);

        Lemma lemma;
        if (optionalLemma.isPresent()) {
            lemma = optionalLemma.get();
            lemma.setFrequency(lemma.getFrequency() + 1);
        } else {
            lemma = new Lemma();
            lemma.setLemma(lemmaText);
            lemma.setFrequency(1);
            lemma.setSite(site);
        }

        return lemmaRepository.save(lemma);
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
}