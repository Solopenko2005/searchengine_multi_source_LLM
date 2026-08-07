package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.IndexingJob;
import searchengine.model.IndexingJobState;

import java.util.Collection;
import java.util.List;

public interface IndexingJobRepository extends JpaRepository<IndexingJob, String> {
    List<IndexingJob> findAllByOrderByCreatedAtDesc();
    List<IndexingJob> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<IndexingJob> findByStateIn(Collection<IndexingJobState> states);
    boolean existsBySiteId(Integer siteId);
    boolean existsBySourceUrlAndStateIn(String sourceUrl, Collection<IndexingJobState> states);
}
