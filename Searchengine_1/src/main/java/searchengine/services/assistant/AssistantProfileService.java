package searchengine.services.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.dto.assistant.AssistantProfileResponse;
import searchengine.model.AssistantProfile;
import searchengine.model.Page;
import searchengine.model.SourceType;
import searchengine.repository.AssistantProfileRepository;
import searchengine.repository.PageRepository;
import searchengine.services.CurrentUserService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Stores the user's durable RAG instructions and document scope. */
@Service
@RequiredArgsConstructor
public class AssistantProfileService {

    private static final int MAX_INSTRUCTIONS_LENGTH = 3000;

    private final AssistantProfileRepository profileRepository;
    private final PageRepository pageRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public AssistantProfileResponse get() {
        String ownerId = currentUserService.getUserId();
        return profileRepository.findByOwnerId(ownerId)
                .map(profile -> new AssistantProfileResponse(true, profile.getInstructions(),
                        validateDocumentIds(parseIds(profile.getDocumentIds()))))
                .orElseGet(() -> new AssistantProfileResponse(true, "", accessibleDocumentIds()));
    }

    @Transactional
    public AssistantProfileResponse save(AssistantProfileRequest request) {
        String ownerId = currentUserService.getUserId();
        String instructions = request == null || request.getInstructions() == null
                ? "" : request.getInstructions().trim();
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new IllegalArgumentException("Инструкции профиля не должны превышать 3000 символов");
        }

        List<Integer> documentIds = validateDocumentIds(
                request == null ? List.of() : request.getDocumentIds());
        AssistantProfile profile = profileRepository.findByOwnerId(ownerId)
                .orElseGet(AssistantProfile::new);
        profile.setOwnerId(ownerId);
        profile.setInstructions(instructions);
        profile.setDocumentIds(joinIds(documentIds));
        profileRepository.save(profile);
        return new AssistantProfileResponse(true, instructions, documentIds);
    }

    @Transactional(readOnly = true)
    public ResolvedProfile resolve(List<Integer> requestDocumentIds, String requestInstructions) {
        AssistantProfileResponse stored = get();
        String instructions = requestInstructions != null && !requestInstructions.isBlank()
                ? requestInstructions.trim() : stored.getInstructions();
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            instructions = instructions.substring(0, MAX_INSTRUCTIONS_LENGTH);
        }
        List<Integer> ids = requestDocumentIds != null && !requestDocumentIds.isEmpty()
                ? validateDocumentIds(requestDocumentIds) : stored.getDocumentIds();
        return new ResolvedProfile(instructions, ids);
    }

    private List<Integer> accessibleDocumentIds() {
        return pageRepository.findBySiteSourceTypeOrderByIdDesc(SourceType.DOCUMENT).stream()
                .filter(currentUserService::canAccess)
                .map(Page::getId)
                .collect(Collectors.toList());
    }

    private List<Integer> validateDocumentIds(List<Integer> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Integer> requested = requestedIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Integer> accessible = pageRepository.findBySiteSourceTypeOrderByIdDesc(SourceType.DOCUMENT).stream()
                .filter(currentUserService::canAccess)
                .map(Page::getId)
                .filter(requested::contains)
                .collect(Collectors.toSet());
        return requested.stream().filter(accessible::contains).collect(Collectors.toList());
    }

    private List<Integer> parseIds(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        List<Integer> result = new ArrayList<>();
        for (String part : value.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private String joinIds(List<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public static class ResolvedProfile {
        private final String instructions;
        private final List<Integer> documentIds;

        public ResolvedProfile(String instructions, List<Integer> documentIds) {
            this.instructions = instructions;
            this.documentIds = List.copyOf(documentIds);
        }

        public String getInstructions() {
            return instructions;
        }

        public List<Integer> getDocumentIds() {
            return documentIds;
        }
    }
}
