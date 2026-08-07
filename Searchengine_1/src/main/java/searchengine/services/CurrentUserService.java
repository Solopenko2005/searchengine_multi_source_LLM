package searchengine.services;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import searchengine.model.Page;
import searchengine.model.SourceType;

/** Единая точка определения владельца данных в basic- и JWT-режимах. */
@Service
public class CurrentUserService {

    public String getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new IllegalStateException("Пользователь не аутентифицирован");
        }
        return authentication.getName();
    }

    public boolean canAccess(Page page) {
        if (page == null || page.getSite() == null
                || page.getSite().getSourceType() != SourceType.DOCUMENT) {
            return true;
        }
        if (isAdmin()) {
            return true;
        }
        if (page.getOwnerId() == null || page.getOwnerId().isBlank()) {
            return false;
        }
        return page.getOwnerId().equals(getUserId());
    }

    public boolean isAdmin() {
        return hasRole("ROLE_ADMIN");
    }

    private boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
