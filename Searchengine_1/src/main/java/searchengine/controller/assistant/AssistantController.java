package searchengine.controller.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.assistant.AssistantChatResponse;
import searchengine.dto.assistant.ChatRequest;
import searchengine.dto.assistant.TopicsSummaryResponse;
import searchengine.dto.assistant.AssistantProfileRequest;
import searchengine.dto.assistant.AssistantProfileResponse;
import searchengine.services.assistant.AssistantService;
import searchengine.services.assistant.AssistantProfileService;

import java.util.HashMap;
import java.util.Map;

/**
 * REST-контроллер LLM-ассистента.
 * <p>
 * Эндпоинты:
 *  - POST /api/assistant/chat   — вопрос по документам, ответ со ссылками на источники;
 *  - GET  /api/assistant/topics — обзор популярных тематик по документам;
 *  - GET  /api/assistant/status — настроена ли языковая модель.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantProfileService profileService;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody ChatRequest request) {
        return assistantService.chat(request);
    }

    @GetMapping("/topics")
    public TopicsSummaryResponse topics() {
        return assistantService.topics();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("result", true);
        map.put("llmConfigured", assistantService.isLlmConfigured());
        map.put("provider", "OpenAI");
        map.put("model", assistantService.getLlmModel());
        return map;
    }

    @GetMapping("/profile")
    public AssistantProfileResponse profile() {
        return profileService.get();
    }

    @PutMapping("/profile")
    public AssistantProfileResponse saveProfile(@RequestBody AssistantProfileRequest request) {
        return profileService.save(request);
    }
}
