package searchengine.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import searchengine.model.AssistantChunk;
import searchengine.model.AssistantChunkStatus;

import java.util.Collection;
import java.util.List;

public interface AssistantChunkRepository extends JpaRepository<AssistantChunk, Long> {
    boolean existsByPageId(Integer pageId);

    long countByStatus(AssistantChunkStatus status);

    List<AssistantChunk> findByStatusOrderByIdAsc(AssistantChunkStatus status, Pageable pageable);

    @Query("SELECT c FROM AssistantChunk c JOIN FETCH c.page p WHERE c.status = :status ORDER BY c.id")
    List<AssistantChunk> findByStatusWithPage(@Param("status") AssistantChunkStatus status,
                                               Pageable pageable);

    @Query("SELECT DISTINCT c FROM AssistantChunk c JOIN FETCH c.page p JOIN FETCH p.site " +
            "WHERE c.id IN :ids AND c.status = :status")
    List<AssistantChunk> findReadyWithPageByIds(@Param("ids") Collection<Long> ids,
                                                 @Param("status") AssistantChunkStatus status);

    @Query("SELECT c FROM AssistantChunk c JOIN FETCH c.page p JOIN FETCH p.site " +
            "WHERE p.id IN :pageIds AND c.status = :status ORDER BY p.id, c.chunkIndex")
    List<AssistantChunk> findByPageIdsWithPage(@Param("pageIds") Collection<Integer> pageIds,
                                                @Param("status") AssistantChunkStatus status);

    @Query(value = "SELECT ranked.id FROM (" +
            "SELECT c.id, c.site_id, ROW_NUMBER() OVER (PARTITION BY c.site_id " +
            "ORDER BY LENGTH(c.content) DESC, c.chunk_index, c.id) rn " +
            "FROM assistant_chunk c WHERE c.site_id IN (:siteIds) AND c.status = 'READY'" +
            ") ranked WHERE ranked.rn <= :perSource ORDER BY ranked.rn, ranked.site_id LIMIT :limit",
            nativeQuery = true)
    List<Long> findRepresentativeReadyIds(@Param("siteIds") Collection<Integer> siteIds,
                                           @Param("perSource") int perSource,
                                           @Param("limit") int limit);

    long countBySiteIdIn(Collection<Integer> siteIds);

    long countBySiteIdInAndStatus(Collection<Integer> siteIds, AssistantChunkStatus status);

    @Query("SELECT COUNT(DISTINCT c.page.id) FROM AssistantChunk c " +
            "WHERE c.siteId IN :siteIds AND c.status = :status")
    long countDistinctPagesBySiteIdsAndStatus(@Param("siteIds") Collection<Integer> siteIds,
                                               @Param("status") AssistantChunkStatus status);

    @Query("SELECT COUNT(DISTINCT c.page.id) FROM AssistantChunk c " +
            "WHERE c.siteId IN :siteIds AND c.status IN :statuses")
    long countDistinctPagesBySiteIdsAndStatusIn(@Param("siteIds") Collection<Integer> siteIds,
                                                 @Param("statuses") Collection<AssistantChunkStatus> statuses);

    @Modifying
    @Query("UPDATE AssistantChunk c SET c.status = :pending, c.errorMessage = null " +
            "WHERE c.status = :failed")
    int resetFailed(@Param("failed") AssistantChunkStatus failed,
                    @Param("pending") AssistantChunkStatus pending);
}
