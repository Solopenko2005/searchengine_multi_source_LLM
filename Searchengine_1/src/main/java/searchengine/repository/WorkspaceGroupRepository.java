package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.WorkspaceGroup;

import java.util.List;
import java.util.Optional;

public interface WorkspaceGroupRepository extends JpaRepository<WorkspaceGroup, Long> {
    List<WorkspaceGroup> findByOwnerIdOrderByCreatedAtAsc(String ownerId);
    Optional<WorkspaceGroup> findFirstByOwnerIdOrderByCreatedAtAsc(String ownerId);
    Optional<WorkspaceGroup> findByInviteCode(String inviteCode);
}
