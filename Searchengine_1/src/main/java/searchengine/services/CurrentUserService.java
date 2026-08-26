package searchengine.services;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import searchengine.model.Page;
import searchengine.model.SourceType;
import searchengine.model.Site;
import searchengine.repository.WorkspaceMembershipRepository;

import java.util.LinkedHashSet;
import java.util.Set;

/** Единая точка определения владельца данных в basic- и JWT-режимах. */
@Service
public class CurrentUserService {

    private final WorkspaceMembershipRepository membershipRepository;

    public CurrentUserService(WorkspaceMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    public String getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new IllegalStateException("Пользователь не аутентифицирован");
        }
        return authentication.getName();
    }

    public boolean canAccess(Page page) {
        if (page == null || page.getSite() == null) return false;
        if (page.getOwnerId() != null && !page.getOwnerId().isBlank()) {
            return accessibleOwnerIds().contains(page.getOwnerId());
        }
        if (page.getSite().getOwnerId() != null && !page.getSite().getOwnerId().isBlank()) {
            return canAccess(page.getSite());
        }
        // Sources created before workspace ownership was introduced are available
        // only to administrators. An administrator must never gain access to a
        // different administrator's explicitly owned source.
        return isAdmin();
    }

    public boolean canAccess(Site site) {
        if (site == null) return false;
        String ownerId = site.getOwnerId();
        if (ownerId == null || ownerId.isBlank()) return isAdmin();
        return accessibleOwnerIds().contains(ownerId);
    }

    public Set<String> accessibleOwnerIds() {
        String userId = getUserId();
        Set<String> owners = new LinkedHashSet<>();
        owners.add(userId);
        membershipRepository.findByUserIdOrderByJoinedAtAsc(userId).stream()
                .map(membership -> membership.getGroup().getOwnerId())
                .filter(owner -> owner != null && !owner.isBlank())
                .forEach(owners::add);
        return owners;
    }

    public boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    public boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
