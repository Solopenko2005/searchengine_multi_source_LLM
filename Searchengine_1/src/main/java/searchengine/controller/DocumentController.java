package searchengine.controller;

import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import searchengine.model.Page;
import searchengine.model.SourceType;
import searchengine.repository.PageRepository;
import searchengine.services.Lemmatizer;
import searchengine.services.CurrentUserService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Просмотр загруженных документов и подсветка слов, соответствующих леммам запроса. */
@RestController
@RequiredArgsConstructor
public class DocumentController {

    private static final Pattern WORD_OR_SEPARATOR = Pattern.compile("[\\p{L}\\d]+|[^\\p{L}\\d]+");

    private final PageRepository pageRepository;
    private final Lemmatizer lemmatizer;
    private final CurrentUserService currentUserService;

    @GetMapping("/api/documents")
    public Map<String, Object> documents() {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Page page : pageRepository.findBySiteSourceTypeOrderByIdDesc(SourceType.DOCUMENT)) {
            if (!currentUserService.canAccess(page)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", page.getId());
            item.put("name", page.getOriginalFileName() != null ? page.getOriginalFileName() : page.getPath());
            item.put("indexed", page.getCode() != null && page.getCode() == 200);
            item.put("topics", page.getTopicCount() != null ? page.getTopicCount() : 0);
            item.put("url", "/documents/" + page.getId());
            data.add(item);
        }
        return Map.of("result", true, "data", data);
    }

    @GetMapping(value = "/documents/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(@PathVariable Long id,
                                       @RequestParam(defaultValue = "") String query) {
        Page page = pageRepository.findById(id).orElse(null);
        if (page == null || page.getSite() == null || page.getSite().getSourceType() != SourceType.DOCUMENT) {
            return ResponseEntity.notFound().build();
        }
        if (!currentUserService.canAccess(page)) {
            return ResponseEntity.notFound().build();
        }

        String title = page.getOriginalFileName() != null ? page.getOriginalFileName() : page.getPath();
        String plainText = Jsoup.parse(page.getContent()).text();
        String highlighted = highlightByLemmas(plainText, query);
        String html = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(title) + "</title><style>"
                + "body{font:16px/1.65 system-ui,sans-serif;max-width:1000px;margin:32px auto;padding:0 24px;color:#1d2733}"
                + "h1{font-size:24px}mark{background:#ffdf6e;color:#1d2733;padding:1px 2px;border-radius:2px}"
                + ".query{color:#52606d;margin-bottom:24px}.document{white-space:pre-wrap}"
                + "</style></head><body><h1>" + escapeHtml(title) + "</h1>"
                + (query.isBlank() ? "" : "<div class=\"query\">Подсветка по запросу: <b>"
                    + escapeHtml(query) + "</b></div>")
                + "<article class=\"document\">" + highlighted + "</article></body></html>";

        return ResponseEntity.ok()
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'self'")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    private String highlightByLemmas(String text, String query) {
        if (query == null || query.isBlank()) {
            return escapeHtml(text);
        }
        Set<String> queryLemmas = lemmatizer.getLemmas(query).keySet();
        if (queryLemmas.isEmpty()) {
            return escapeHtml(text);
        }

        StringBuilder result = new StringBuilder(text.length() + 256);
        Matcher matcher = WORD_OR_SEPARATOR.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            boolean word = token.codePoints().anyMatch(Character::isLetterOrDigit);
            boolean matches = false;
            if (word) {
                try {
                    Set<String> tokenLemmas = lemmatizer.getWordLemmas(token).stream()
                            .collect(Collectors.toSet());
                    matches = tokenLemmas.stream().anyMatch(queryLemmas::contains);
                } catch (Exception ignored) {
                }
            }
            String safeToken = escapeHtml(token);
            result.append(matches ? "<mark>" + safeToken + "</mark>" : safeToken);
        }
        return result.toString();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
