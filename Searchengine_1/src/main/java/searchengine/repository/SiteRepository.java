package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;
import searchengine.model.Status;

import java.util.List;
import java.util.Collection;
import java.util.Optional;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {

    List<Site> findByStatus(Status status);

    long countByStatus(Status status);

    Site findSiteByUrl(String url);

    Optional<Site> findByUrl(String siteUrl);

    List<Site> findAllByOrderByIdDesc();

    @Query("SELECT s FROM Site s WHERE s.ownerId IN :ownerIds " +
            "OR (:includeLegacy = true AND (s.ownerId IS NULL OR s.ownerId = '')) " +
            "ORDER BY s.id DESC")
    List<Site> findAccessibleByOwnerIds(@Param("ownerIds") Collection<String> ownerIds,
                                        @Param("includeLegacy") boolean includeLegacy);

}
