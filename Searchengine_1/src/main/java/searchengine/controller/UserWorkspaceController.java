package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import searchengine.model.Site;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.services.CurrentUserService;
import searchengine.services.SourceDeletionService;
import searchengine.services.WorkspaceGroupService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Read-only data for the social-style profile card and the assistant workspace. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserWorkspaceController {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final CurrentUserService currentUserService;
    private final SourceDeletionService sourceDeletionService;
    private final WorkspaceGroupService groupService;

    @GetMapping("/sources")
    public Map<String, Object> sources() {
        if (currentUserService.isAdmin()) groupService.ensureDefaultGroup();
        return Map.of("result", true, "data", buildSourceData());
    }

    private List<Map<String, Object>> buildSourceData() {
        Set<String> ownerIds = currentUserService.accessibleOwnerIds();
        boolean includeLegacy = currentUserService.isAdmin();
        List<Site> sites = siteRepository.findAccessibleByOwnerIds(ownerIds, includeLegacy);
        List<Integer> siteIds = sites.stream().map(Site::getId).collect(Collectors.toList());
        Map<Integer, PageRepository.SitePageSummary> summaries = new HashMap<>();
        if (!siteIds.isEmpty()) {
            for (PageRepository.SitePageSummary summary : pageRepository.summarizeAccessiblePages(
                    siteIds, ownerIds, includeLegacy)) {
                summaries.put(summary.getSiteId(), summary);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Site site : sites) {
            PageRepository.SitePageSummary summary = summaries.get(site.getId());
            long pages = summary == null || summary.getPageCount() == null ? 0 : summary.getPageCount();
            long ready = summary == null || summary.getReadyPageCount() == null ? 0 : summary.getReadyPageCount();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", site.getId());
            item.put("name", site.getName() == null || site.getName().isBlank() ? site.getUrl() : site.getName());
            item.put("url", site.getUrl());
            item.put("type", site.getSourceType() == null ? "WEBSITE" : site.getSourceType().name());
            item.put("pages", pages);
            item.put("readyPages", ready);
            item.put("indexed", ready > 0);
            item.put("status", site.getStatus() == null ? "UNKNOWN" : site.getStatus().name());
            item.put("error", site.getLastError());
            item.put("canDelete", currentUserService.isAdmin());
            items.add(item);
        }
        return items;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        if (currentUserService.isAdmin()) groupService.ensureDefaultGroup();
        List<Map<String, Object>> sources = buildSourceData();
        int pageCount = sources.stream().mapToInt(item -> ((Number) item.get("pages")).intValue()).sum();
        String username = authentication.getName();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.replaceFirst("^ROLE_", ""))
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", true);
        result.put("username", username);
        result.put("displayName", displayName(username));
        result.put("initials", initials(username));
        result.put("roles", roles);
        result.put("admin", currentUserService.isAdmin());
        result.put("sources", sources.size());
        result.put("sourceData", sources);
        result.put("pages", pageCount);
        result.put("groups", groupService.listForCurrentUser().size());
        return result;
    }

    @DeleteMapping("/sources/{sourceId}")
    public Map<String, Object> deleteSource(@PathVariable int sourceId) {
        return sourceDeletionService.delete(List.of(sourceId));
    }

    @DeleteMapping("/sources")
    public Map<String, Object> deleteSources(@RequestParam List<Integer> ids) {
        return sourceDeletionService.delete(ids);
    }

    private String displayName(String username) {
        if (username == null || username.isBlank()) return "Пользователь";
        String base = username.contains("@") ? username.substring(0, username.indexOf('@')) : username;
        return java.util.Arrays.stream(base.split("[._-]+"))
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
                .collect(Collectors.joining(" "));
    }

    private String initials(String username) {
        String displayName = displayName(username);
        return java.util.Arrays.stream(displayName.split("\\s+"))
                .filter(part -> !part.isBlank())
                .limit(2)
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
    }
}
