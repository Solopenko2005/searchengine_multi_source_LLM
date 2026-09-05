package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;

import java.util.Optional;
import java.util.List;
import java.util.Collection;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
    interface ScopeRevision {
        Long getPageCount();
        Long getMaxPageId();
        Long getSumPageIds();
    }
    interface SitePageSummary {
        Integer getSiteId();
        Long getPageCount();
        Long getReadyPageCount();
    }

    int countBySite(Site site);

    boolean existsBySiteAndPath(Site site, String path);

    @Query("SELECT p FROM Page p WHERE p.site.id = :siteId AND p.path = :path")
    Optional<Page> findBySiteAndPath(@Param("siteId") int siteId, @Param("path") String path);

    @Query("SELECT p FROM Page p WHERE p.site.id = :siteId AND p.path = :path AND p.ownerId = :ownerId")
    Optional<Page> findBySiteAndPathAndOwnerId(@Param("siteId") int siteId,
                                               @Param("path") String path,
                                               @Param("ownerId") String ownerId);

    List<Page> findAllByOrderByIdDesc();

    List<Page> findBySiteSourceTypeOrderByIdDesc(SourceType sourceType);

    @Query("SELECT p FROM Page p WHERE p.site.id IN :siteIds ORDER BY p.id DESC")
    List<Page> findBySiteIdsOrderByIdDesc(@Param("siteIds") Collection<Integer> siteIds);

    List<Page> findBySiteIdOrderByIdDesc(int siteId);

    @Query("SELECT p.site.id AS siteId, COUNT(p.id) AS pageCount, " +
            "SUM(CASE WHEN p.code < 400 THEN 1 ELSE 0 END) AS readyPageCount " +
            "FROM Page p WHERE p.site.id IN :siteIds AND " +
            "(p.ownerId IN :ownerIds " +
            "OR ((p.ownerId IS NULL OR p.ownerId = '') AND p.site.ownerId IN :ownerIds) " +
            "OR (:includeLegacy = true AND (p.ownerId IS NULL OR p.ownerId = '') " +
            "AND (p.site.ownerId IS NULL OR p.site.ownerId = ''))) " +
            "GROUP BY p.site.id")
    List<SitePageSummary> summarizeAccessiblePages(@Param("siteIds") Collection<Integer> siteIds,
                                                   @Param("ownerIds") Collection<String> ownerIds,
                                                   @Param("includeLegacy") boolean includeLegacy);

    @Query("SELECT DISTINCT p.site.id FROM Page p WHERE p.id IN :pageIds AND " +
            "(p.ownerId IN :ownerIds " +
            "OR ((p.ownerId IS NULL OR p.ownerId = '') AND p.site.ownerId IN :ownerIds) " +
            "OR (:includeLegacy = true AND (p.ownerId IS NULL OR p.ownerId = '') " +
            "AND (p.site.ownerId IS NULL OR p.site.ownerId = ''))) ORDER BY p.site.id")
    List<Integer> findAccessibleSiteIdsByPageIds(@Param("pageIds") Collection<Integer> pageIds,
                                                  @Param("ownerIds") Collection<String> ownerIds,
                                                  @Param("includeLegacy") boolean includeLegacy);

    @Query("SELECT p.id FROM Page p WHERE p.id IN :pageIds AND p.site.sourceType = :sourceType AND " +
            "(p.ownerId IN :ownerIds " +
            "OR ((p.ownerId IS NULL OR p.ownerId = '') AND p.site.ownerId IN :ownerIds) " +
            "OR (:includeLegacy = true AND (p.ownerId IS NULL OR p.ownerId = '') " +
            "AND (p.site.ownerId IS NULL OR p.site.ownerId = ''))) ORDER BY p.id DESC")
    List<Integer> findAccessiblePageIds(@Param("pageIds") Collection<Integer> pageIds,
                                        @Param("sourceType") SourceType sourceType,
                                        @Param("ownerIds") Collection<String> ownerIds,
                                        @Param("includeLegacy") boolean includeLegacy);

    @Query("SELECT p FROM Page p WHERE p.site.id IN :siteIds AND " +
            "(p.ownerId IN :ownerIds " +
            "OR ((p.ownerId IS NULL OR p.ownerId = '') AND p.site.ownerId IN :ownerIds) " +
            "OR (:includeLegacy = true AND (p.ownerId IS NULL OR p.ownerId = '') " +
            "AND (p.site.ownerId IS NULL OR p.site.ownerId = ''))) ORDER BY p.id DESC")
    List<Page> findRecentAccessibleBySiteIds(@Param("siteIds") Collection<Integer> siteIds,
                                             @Param("ownerIds") Collection<String> ownerIds,
                                             @Param("includeLegacy") boolean includeLegacy,
                                             Pageable pageable);

    @Query("SELECT p FROM Page p WHERE p.site.id = :siteId AND " +
            "(p.ownerId IN :ownerIds " +
            "OR ((p.ownerId IS NULL OR p.ownerId = '') AND p.site.ownerId IN :ownerIds) " +
            "OR (:includeLegacy = true AND (p.ownerId IS NULL OR p.ownerId = '') " +
            "AND (p.site.ownerId IS NULL OR p.site.ownerId = ''))) ORDER BY p.id DESC")
    List<Page> findRepresentativeAccessiblePage(@Param("siteId") int siteId,
                                                @Param("ownerIds") Collection<String> ownerIds,
                                                @Param("includeLegacy") boolean includeLegacy,
                                                Pageable pageable);

    @Query("SELECT p.id FROM Page p WHERE p.code < 400 AND " +
            "NOT EXISTS (SELECT c.id FROM AssistantChunk c WHERE c.page = p) ORDER BY p.id")
    List<Integer> findIdsWithoutAssistantChunks(Pageable pageable);

    @Query("SELECT COUNT(p.id) FROM Page p WHERE p.site.id IN :siteIds AND p.code < 400")
    long countAssistantIndexableBySiteIds(@Param("siteIds") Collection<Integer> siteIds);

    @Query("SELECT COUNT(p.id) AS pageCount, COALESCE(MAX(p.id), 0) AS maxPageId, " +
            "COALESCE(SUM(p.id), 0) AS sumPageIds FROM Page p WHERE p.site.id IN :siteIds")
    ScopeRevision scopeRevision(@Param("siteIds") Collection<Integer> siteIds);

    long countBySiteIdIn(Collection<Integer> siteIds);

}
