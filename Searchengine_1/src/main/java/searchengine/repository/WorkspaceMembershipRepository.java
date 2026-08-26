package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.WorkspaceMembership;

import java.util.List;
import java.util.Optional;

public interface WorkspaceMembershipRepository extends JpaRepository<WorkspaceMembership, Long> {
    List<WorkspaceMembership> findByUserIdOrderByJoinedAtAsc(String userId);
    List<WorkspaceMembership> findByGroupIdOrderByJoinedAtAsc(Long groupId);
    Optional<WorkspaceMembership> findByGroupIdAndUserId(Long groupId, String userId);
    void deleteByGroupIdAndUserId(Long groupId, String userId);
    void deleteByGroupId(Long groupId);
    long countByGroupId(Long groupId);
}
