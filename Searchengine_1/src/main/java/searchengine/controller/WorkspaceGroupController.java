package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import searchengine.services.WorkspaceGroupService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class WorkspaceGroupController {
    private final WorkspaceGroupService groupService;

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("result", true, "groups", groupService.listForCurrentUser());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> request) {
        return withInviteUrl(groupService.create(request.get("name")));
    }

    @PostMapping("/{groupId}/members")
    public Map<String, Object> addMember(@PathVariable long groupId,
                                          @RequestBody Map<String, String> request) {
        return withInviteUrl(groupService.addMember(groupId, request.get("userId")));
    }

    @DeleteMapping("/{groupId}/members")
    public Map<String, Object> removeMember(@PathVariable long groupId,
                                             @RequestParam String userId) {
        groupService.removeMember(groupId, userId);
        return Map.of("result", true, "message", "Пользователь исключён из группы");
    }

    @DeleteMapping("/{groupId}")
    public Map<String, Object> delete(@PathVariable long groupId) {
        groupService.delete(groupId);
        return Map.of("result", true, "message", "Группа удалена");
    }

    @PostMapping("/{groupId}/invite")
    public Map<String, Object> refreshInvite(@PathVariable long groupId) {
        return withInviteUrl(groupService.refreshInvite(groupId));
    }

    @GetMapping("/invitations/{code}")
    public Map<String, Object> invitation(@PathVariable String code) {
        return groupService.invitation(code);
    }

    @PostMapping("/invitations/{code}/accept")
    public Map<String, Object> accept(@PathVariable String code) {
        return groupService.accept(code);
    }

    private Map<String, Object> withInviteUrl(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        Object code = source.get("inviteCode");
        if (code != null && !String.valueOf(code).isBlank()) {
            String root = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            result.put("inviteUrl", root + "/app?join=" + code);
        }
        result.put("result", true);
        return result;
    }
}
