package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Site;
import searchengine.model.WorkspaceGroup;
import searchengine.model.WorkspaceMembership;
import searchengine.repository.SiteRepository;
import searchengine.repository.WorkspaceGroupRepository;
import searchengine.repository.WorkspaceMembershipRepository;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkspaceGroupService {
    private static final String DEFAULT_GROUP_NAME = "Основная группа";
    private final WorkspaceGroupRepository groupRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final SiteRepository siteRepository;
    private final CurrentUserService currentUserService;

    @Transactional
    public WorkspaceGroup ensureDefaultGroup() {
        requireAdmin();
        String owner = currentUserService.getUserId();
        List<WorkspaceGroup> ownedGroups = groupRepository.findByOwnerIdOrderByCreatedAtAsc(owner);
        WorkspaceGroup group = ownedGroups.isEmpty() ? createEntity(DEFAULT_GROUP_NAME, owner) : ownedGroups.get(0);
        mergeDuplicateDefaultGroups(group, ownedGroups);
        claimLegacySources(owner);
        return group;
    }

    @Transactional
    public Map<String, Object> create(String name) {
        requireAdmin();
        String normalized = normalizeName(name);
        WorkspaceGroup group = createEntity(normalized, currentUserService.getUserId());
        return toDto(group, true);
    }

    @Transactional
    public List<Map<String, Object>> listForCurrentUser() {
        String userId = currentUserService.getUserId();
        if (currentUserService.isAdmin()) {
            ensureDefaultGroup();
            return groupRepository.findByOwnerIdOrderByCreatedAtAsc(userId).stream()
                    .map(group -> toDto(group, true)).toList();
        }
        return membershipRepository.findByUserIdOrderByJoinedAtAsc(userId).stream()
                .map(WorkspaceMembership::getGroup)
                .map(group -> toDto(group, false)).toList();
    }

    @Transactional
    public Map<String, Object> addMember(long groupId, String userId) {
        WorkspaceGroup group = ownedGroup(groupId);
        String normalized = normalizeUserId(userId);
        if (normalized.equalsIgnoreCase(group.getOwnerId())) {
            throw new IllegalArgumentException("Администратор уже является владельцем группы");
        }
        membershipRepository.findByGroupIdAndUserId(groupId, normalized).orElseGet(() -> {
            WorkspaceMembership membership = new WorkspaceMembership();
            membership.setGroup(group);
            membership.setUserId(normalized);
            membership.setJoinedAt(LocalDateTime.now());
            return membershipRepository.save(membership);
        });
        return toDto(group, true);
    }

    @Transactional
    public void removeMember(long groupId, String userId) {
        ownedGroup(groupId);
        membershipRepository.deleteByGroupIdAndUserId(groupId, normalizeUserId(userId));
    }

    @Transactional
    public void delete(long groupId) {
        WorkspaceGroup group = ownedGroup(groupId);
        List<WorkspaceGroup> ownedGroups = groupRepository.findByOwnerIdOrderByCreatedAtAsc(group.getOwnerId());
        if (ownedGroups.size() <= 1) {
            throw new IllegalArgumentException("Нельзя удалить единственную группу администратора");
        }
        membershipRepository.deleteByGroupId(groupId);
        groupRepository.delete(group);
    }

    @Transactional
    public Map<String, Object> refreshInvite(long groupId) {
        WorkspaceGroup group = ownedGroup(groupId);
        group.setInviteCode(UUID.randomUUID().toString());
        return toDto(groupRepository.save(group), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> invitation(String code) {
        WorkspaceGroup group = invitedGroup(code);
        return Map.of("result", true, "groupId", group.getId(), "name", group.getName(),
                "owner", group.getOwnerId());
    }

    @Transactional
    public Map<String, Object> accept(String code) {
        WorkspaceGroup group = invitedGroup(code);
        String userId = normalizeUserId(currentUserService.getUserId());
        if (userId.equalsIgnoreCase(group.getOwnerId())) return toDto(group, currentUserService.isAdmin());
        membershipRepository.findByGroupIdAndUserId(group.getId(), userId).orElseGet(() -> {
            WorkspaceMembership membership = new WorkspaceMembership();
            membership.setGroup(group);
            membership.setUserId(userId);
            membership.setJoinedAt(LocalDateTime.now());
            return membershipRepository.save(membership);
        });
        return toDto(group, false);
    }

    private WorkspaceGroup createEntity(String name, String owner) {
        WorkspaceGroup group = new WorkspaceGroup();
        group.setName(name);
        group.setOwnerId(owner);
        group.setInviteCode(UUID.randomUUID().toString());
        group.setCreatedAt(LocalDateTime.now());
        return groupRepository.save(group);
    }

    private void mergeDuplicateDefaultGroups(WorkspaceGroup canonical, List<WorkspaceGroup> ownedGroups) {
        if (!DEFAULT_GROUP_NAME.equalsIgnoreCase(canonical.getName())) return;
        for (WorkspaceGroup duplicate : ownedGroups) {
            if (Objects.equals(canonical.getId(), duplicate.getId())
                    || !DEFAULT_GROUP_NAME.equalsIgnoreCase(duplicate.getName())) continue;
            for (WorkspaceMembership oldMembership : membershipRepository.findByGroupIdOrderByJoinedAtAsc(duplicate.getId())) {
                String userId = oldMembership.getUserId();
                if (membershipRepository.findByGroupIdAndUserId(canonical.getId(), userId).isEmpty()) {
                    WorkspaceMembership membership = new WorkspaceMembership();
                    membership.setGroup(canonical);
                    membership.setUserId(userId);
                    membership.setJoinedAt(oldMembership.getJoinedAt());
                    membershipRepository.save(membership);
                }
            }
            membershipRepository.deleteByGroupId(duplicate.getId());
            groupRepository.delete(duplicate);
        }
    }

    private WorkspaceGroup ownedGroup(long id) {
        requireAdmin();
        WorkspaceGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Группа не найдена"));
        if (!group.getOwnerId().equalsIgnoreCase(currentUserService.getUserId())) {
            throw new SecurityException("Нельзя управлять чужой группой");
        }
        return group;
    }

    private WorkspaceGroup invitedGroup(String code) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Код приглашения отсутствует");
        return groupRepository.findByInviteCode(code.trim())
                .orElseThrow(() -> new IllegalArgumentException("Приглашение недействительно"));
    }

    private Map<String, Object> toDto(WorkspaceGroup group, boolean includeMembers) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", group.getId());
        dto.put("name", group.getName());
        dto.put("owner", group.getOwnerId());
        dto.put("memberCount", membershipRepository.countByGroupId(group.getId()));
        dto.put("inviteCode", includeMembers ? group.getInviteCode() : "");
        dto.put("members", includeMembers
                ? membershipRepository.findByGroupIdOrderByJoinedAtAsc(group.getId()).stream()
                    .map(WorkspaceMembership::getUserId).toList()
                : List.of());
        return dto;
    }

    private void claimLegacySources(String owner) {
        for (Site site : siteRepository.findAll()) {
            if (site.getOwnerId() == null || site.getOwnerId().isBlank()) {
                site.setOwnerId(owner);
                siteRepository.save(site);
            }
        }
    }

    private String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.length() < 2 || value.length() > 120) {
            throw new IllegalArgumentException("Название группы должно содержать от 2 до 120 символов");
        }
        return value;
    }

    private String normalizeUserId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("Укажите корректный логин или e-mail пользователя");
        }
        return normalized;
    }

    private void requireAdmin() {
        if (!currentUserService.isAdmin()) throw new SecurityException("Требуются права администратора");
    }
}
