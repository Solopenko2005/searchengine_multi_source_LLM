package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import searchengine.dto.response.IndexingResponse;
import searchengine.services.DocumentIndexingService;
import searchengine.services.SiteIndexingService;
import searchengine.services.IndexingStatusService;
import searchengine.services.IndexingJobService;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class IndexingController {
    private final SiteIndexingService siteIndexingService;
    private final DocumentIndexingService documentIndexingService;
    private final IndexingStatusService indexingStatusService;
    private final IndexingJobService indexingJobService;

    @PostMapping("/startIndexing")
    public Map<String, Object> startIndexing() {
        return siteIndexingService.startIndexing().getBody();
    }

    @PostMapping("/stopIndexing")
    public IndexingResponse stopIndexing() {
        return siteIndexingService.stopIndexing().getBody();
    }

    @PostMapping("/indexPage")
    public Map<String, Object> indexPage(@RequestParam String url) {
        return siteIndexingService.indexPage(url).getBody();
    }

    /**
     * Добавляет новый источник-сайт по URL и сразу запускает его индексацию,
     * не дожидаясь полного цикла "Начать индексацию" и не удаляя уже
     * проиндексированные данные других источников.
     */
    @PostMapping("/addSite")
    public Map<String, Object> addSite(@RequestParam String url,
                                        @RequestParam(required = false) String name) {
        return siteIndexingService.addSite(url, name).getBody();
    }

    /**
     * Загружает и индексирует один или сразу несколько документов (DOCX/PDF)
     * как самостоятельные источники информации.
     */
    @PostMapping(value = "/uploadDocument", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDocument(@RequestParam("files") MultipartFile[] files) {
        Map<String, Object> result = documentIndexingService.submitDocuments(files);
        return Boolean.TRUE.equals(result.get("result"))
                ? ResponseEntity.accepted().body(result)
                : ResponseEntity.badRequest().body(result);
    }

    @PostMapping("/documents/indexing/stop")
    public Map<String, Object> stopDocumentIndexing() {
        return documentIndexingService.stopCurrentUserJob();
    }

    @GetMapping("/documents/indexing/status")
    public Map<String, Object> documentIndexingStatus() {
        return documentIndexingService.currentUserStatus();
    }

    @GetMapping("/indexing/status")
    public Map<String, Object> indexingStatus() {
        return indexingStatusService.getStatus();
    }

    @GetMapping("/indexing/jobs")
    public Map<String, Object> indexingJobs() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", true);
        response.put("summary", indexingJobService.summary());
        response.put("jobs", indexingJobService.listVisible());
        return response;
    }

    @PostMapping("/indexing/jobs/{jobId}/stop")
    public ResponseEntity<Map<String, Object>> stopJob(@PathVariable String jobId) {
        boolean stopped = indexingJobService.requestStop(jobId);
        Map<String, Object> body = stopped
                ? Map.of("result", true, "message", "Остановка задания запрошена")
                : Map.of("result", false, "error", "Активное задание не найдено или недоступно");
        return stopped ? ResponseEntity.ok(body) : ResponseEntity.badRequest().body(body);
    }

    @PostMapping("/indexing/jobs/stop-all")
    public Map<String, Object> stopAllJobs() {
        int count = indexingJobService.requestStopAllVisible();
        return Map.of("result", true, "stopped", count,
                "message", count > 0 ? "Остановка заданий запрошена" : "Активных заданий нет");
    }
}
