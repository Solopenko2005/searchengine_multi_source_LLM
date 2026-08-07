package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;

import java.util.Optional;
import java.util.List;

@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {
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

}
