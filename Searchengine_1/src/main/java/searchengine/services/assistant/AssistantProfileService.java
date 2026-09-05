package searchengine.services.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.dto.assistant.AssistantProfileResponse;
import searchengine.dto.assistant.TopicItem;
import searchengine.model.AssistantProfile;
import searchengine.model.SourceType;
import searchengine.repository.AssistantProfileRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
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
    private final SiteRepository siteRepository;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public AssistantProfileResponse get() {
        String ownerId = currentUserService.getUserId();
        return profileRepository.findByOwnerId(ownerId)
                .map(profile -> {
                    List<Integer> legacyPages = validateDocumentIds(parseIds(profile.getDocumentIds()));
                    List<Integer> selectedSources = validateSourceIds(parseIds(profile.getSourceIds()));
                    if (selectedSources.isEmpty() && !legacyPages.isEmpty()) {
                        selectedSources = sourceIdsForPages(legacyPages);
                    }
                    if (selectedSources.isEmpty()) selectedSources = accessibleSourceIds();
                    return new AssistantProfileResponse(true, profile.getInstructions(),
                            legacyPages, selectedSources);
                })
                .orElseGet(() -> {
                    List<Integer> sources = accessibleSourceIds();
                    return new AssistantProfileResponse(true, "", List.of(), sources);
                });
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
        List<Integer> sourceIds = validateSourceIds(
                request == null ? List.of() : request.getSourceIds());
        if (sourceIds.isEmpty() && !documentIds.isEmpty()) sourceIds = sourceIdsForPages(documentIds);
        if (sourceIds.isEmpty()) sourceIds = accessibleSourceIds();
        AssistantProfile profile = profileRepository.findByOwnerId(ownerId)
                .orElseGet(AssistantProfile::new);
        profile.setOwnerId(ownerId);
        profile.setInstructions(instructions);
        profile.setDocumentIds(joinIds(documentIds));
        profile.setSourceIds(joinIds(sourceIds));
        profileRepository.save(profile);
        return new AssistantProfileResponse(true, instructions, documentIds, sourceIds);
    }

    @Transactional(readOnly = true)
    public ResolvedProfile resolve(List<Integer> requestSourceIds, List<Integer> requestDocumentIds,
                                   String requestInstructions) {
        String ownerId = currentUserService.getUserId();
        AssistantProfile storedProfile = profileRepository.findByOwnerId(ownerId).orElse(null);
        String storedInstructions = storedProfile == null ? "" : storedProfile.getInstructions();
        List<Integer> storedPages = storedProfile == null
                ? List.of() : validateDocumentIds(parseIds(storedProfile.getDocumentIds()));
        List<Integer> storedSources = storedProfile == null
                ? List.of() : validateSourceIds(parseIds(storedProfile.getSourceIds()));
        if (storedSources.isEmpty() && !storedPages.isEmpty()) {
            storedSources = sourceIdsForPages(storedPages);
        }
        String instructions = requestInstructions != null && !requestInstructions.isBlank()
                ? requestInstructions.trim() : storedInstructions;
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            instructions = instructions.substring(0, MAX_INSTRUCTIONS_LENGTH);
        }
        List<Integer> sourceIds = requestSourceIds != null && !requestSourceIds.isEmpty()
                ? validateSourceIds(requestSourceIds) : storedSources;
        List<Integer> documentIds = requestDocumentIds != null && !requestDocumentIds.isEmpty()
                ? validateDocumentIds(requestDocumentIds) : storedPages;
        if ((sourceIds == null || sourceIds.isEmpty())
                && !documentIds.isEmpty()) {
            sourceIds = sourceIdsForPages(documentIds);
        }
        if (sourceIds == null || sourceIds.isEmpty()) sourceIds = accessibleSourceIds();
        return new ResolvedProfile(instructions, sourceIds, documentIds);
    }

    @Transactional
    public void saveDetectedTopics(List<TopicItem> topics) {
        String ownerId = currentUserService.getUserId();
        AssistantProfile profile = profileRepository.findByOwnerId(ownerId)
                .orElseGet(AssistantProfile::new);
        profile.setOwnerId(ownerId);
        List<String> titles = topics == null ? List.of() : topics.stream()
                .map(TopicItem::getTheme)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replace('\n', ' ').replace('\r', ' ').trim())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
        profile.setDetectedTopics(String.join("\n", titles));
        profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<String> getDetectedTopics() {
        return profileRepository.findByOwnerId(currentUserService.getUserId())
                .map(AssistantProfile::getDetectedTopics)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> java.util.Arrays.stream(value.split("\\R"))
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .distinct()
                        .limit(5)
                        .collect(Collectors.toList()))
                .orElseGet(List::of);
    }

    @Transactional
    public void clearDetectedTopics() {
        profileRepository.findByOwnerId(currentUserService.getUserId())
                .filter(profile -> profile.getDetectedTopics() != null
                        && !profile.getDetectedTopics().isBlank())
                .ifPresent(profile -> {
                    profile.setDetectedTopics("");
                    profileRepository.save(profile);
                });
    }

    /** Backward-compatible overload used by older callers/tests. */
    @Transactional(readOnly = true)
    public ResolvedProfile resolve(List<Integer> requestDocumentIds, String requestInstructions) {
        return resolve(List.of(), requestDocumentIds, requestInstructions);
    }

    private List<Integer> accessibleSourceIds() {
        Set<String> ownerIds = currentUserService.accessibleOwnerIds();
        return siteRepository.findAccessibleByOwnerIds(ownerIds, currentUserService.isAdmin()).stream()
                .map(site -> site.getId())
                .collect(Collectors.toList());
    }

    private List<Integer> validateSourceIds(List<Integer> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return new ArrayList<>();
        Set<Integer> requested = requestedIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> accessible = new LinkedHashSet<>(accessibleSourceIds());
        return requested.stream().filter(accessible::contains).collect(Collectors.toList());
    }

    private List<Integer> sourceIdsForPages(List<Integer> pageIds) {
        if (pageIds == null || pageIds.isEmpty()) return new ArrayList<>();
        return pageRepository.findAccessibleSiteIdsByPageIds(new LinkedHashSet<>(pageIds),
                currentUserService.accessibleOwnerIds(), currentUserService.isAdmin());
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
        Set<Integer> accessible = new LinkedHashSet<>(pageRepository.findAccessiblePageIds(
                requested, SourceType.DOCUMENT, currentUserService.accessibleOwnerIds(),
                currentUserService.isAdmin()));
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
        private final List<Integer> sourceIds;
        private final List<Integer> documentIds;

        public ResolvedProfile(String instructions, List<Integer> sourceIds, List<Integer> documentIds) {
            this.instructions = instructions;
            this.sourceIds = List.copyOf(sourceIds);
            this.documentIds = List.copyOf(documentIds);
        }

        public String getInstructions() {
            return instructions;
        }

        public List<Integer> getDocumentIds() {
            return documentIds;
        }

        public List<Integer> getSourceIds() {
            return sourceIds;
        }
    }
}
