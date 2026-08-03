package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import searchengine.dto.response.IndexingResponse;
import searchengine.services.DocumentIndexingService;
import searchengine.services.SiteIndexingService;
import searchengine.services.IndexingStatusService;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
public class IndexingController {
    private final SiteIndexingService siteIndexingService;
    private final DocumentIndexingService documentIndexingService;
    private final IndexingStatusService indexingStatusService;

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
    public Map<String, Object> uploadDocument(@RequestParam("files") MultipartFile[] files) {
        return documentIndexingService.indexDocuments(files);
    }

    @GetMapping("/indexing/status")
    public Map<String, Object> indexingStatus() {
        return indexingStatusService.getStatus();
    }
}
