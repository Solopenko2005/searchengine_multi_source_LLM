package searchengine.services.scientific;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import searchengine.dto.scientific.ScientificArticleDto;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class ScientificCatalogService {

    private static final int MAX_RESULTS = 25;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private final RestTemplate restTemplate;
    private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();

    public ScientificCatalogService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(7))
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "StudentResearchSearch/1.0 (educational project)")
                .build();
    }

    public Map<String, Object> catalog() {
        List<Map<String, String>> providers = List.of(
                provider("crossref", "Crossref", "Все науки", "All disciplines", "Публичный REST API, DOI и метаданные публикаций"),
                provider("europepmc", "Europe PMC", "Биология и медицина", "Biology and medicine", "Статьи, препринты и открытые полные тексты"),
                provider("semantic-scholar", "Semantic Scholar", "Все науки и связи цитирования", "All disciplines and citation links", "Публикации, аннотации и показатели цитирования"),
                provider("arxiv", "arXiv", "Препринты по точным и естественным наукам", "Preprints in STEM and related disciplines", "Открытый Atom API и полные тексты препринтов"),
                provider("doaj", "DOAJ", "Рецензируемые журналы открытого доступа", "Peer-reviewed open-access journals", "Открытый API статей и журналов без платной подписки")
        );
        List<Map<String, String>> categories = List.of(
                category("all", "Все тематики", "All subjects"), category("agriculture", "Сельское хозяйство", "Agriculture"),
                category("biology", "Биология и генетика", "Biology and genetics"), category("medicine", "Медицина", "Medicine"),
                category("computer-science", "Информатика и ИИ", "Computer science and AI"), category("engineering", "Инженерия", "Engineering"),
                category("ecology", "Экология", "Ecology"), category("social-science", "Общественные науки", "Social sciences")
        );
        return Map.of("result", true, "providers", providers, "categories", categories,
                "russianResources", russianResources());
    }

    public List<ScientificArticleDto> search(String provider, String query, String category, int requestedLimit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Введите тему научного поиска");
        int limit = Math.max(1, Math.min(MAX_RESULTS, requestedLimit));
        String expandedQuery = expandQuery(query.trim(), category);
        String normalizedProvider = provider == null ? "crossref" : provider.toLowerCase(Locale.ROOT);
        String cacheKey = normalizedProvider + "|" + expandedQuery.toLowerCase(Locale.ROOT) + "|" + limit;
        CachedSearch cached = searchCache.get(cacheKey);
        if (cached != null && cached.createdAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.articles();
        }
        List<ScientificArticleDto> articles = switch (normalizedProvider) {
            case "crossref" -> crossref(expandedQuery, limit);
            case "europepmc" -> europePmc(expandedQuery, limit);
            case "semantic-scholar" -> semanticScholar(expandedQuery, limit);
            case "arxiv" -> arxiv(expandedQuery, limit);
            case "doaj" -> doaj(expandedQuery, limit);
            default -> throw new IllegalArgumentException("Неизвестный научный каталог");
        };
        articles = articles.stream()
                .sorted(Comparator.comparingInt(this::citationCount).reversed())
                .toList();
        if (searchCache.size() > 300) {
            Instant expiry = Instant.now().minus(CACHE_TTL);
            searchCache.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(expiry));
        }
        searchCache.put(cacheKey, new CachedSearch(Instant.now(), List.copyOf(articles)));
        return articles;
    }

    private List<ScientificArticleDto> crossref(String query, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl("https://api.crossref.org/works")
                .queryParam("query", query).queryParam("rows", limit)
                .queryParam("select", "DOI,title,author,published,URL,abstract,container-title,is-referenced-by-count")
                .build().encode().toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        List<ScientificArticleDto> result = new ArrayList<>();
        JsonNode items = root == null ? null : root.path("message").path("items");
        if (items == null || !items.isArray()) return result;
        for (JsonNode item : items) {
            ScientificArticleDto article = new ScientificArticleDto();
            article.setProvider("Crossref");
            article.setExternalId(text(item, "DOI"));
            article.setTitle(firstText(item.path("title")));
            article.setAuthors(StreamSupport.stream(item.path("author").spliterator(), false)
                    .map(author -> (text(author, "given") + " " + text(author, "family")).trim())
                    .filter(value -> !value.isBlank()).collect(Collectors.toList()));
            article.setYear(year(item.path("published")));
            article.setVenue(firstText(item.path("container-title")));
            article.setAbstractText(cleanHtml(text(item, "abstract")));
            article.setUrl(text(item, "URL"));
            article.setCitationCount(integer(item, "is-referenced-by-count"));
            article.setOpenAccess(false);
            if (usable(article)) result.add(article);
        }
        return result;
    }

    private List<ScientificArticleDto> europePmc(String query, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl(
                        "https://www.ebi.ac.uk/europepmc/webservices/rest/search")
                .queryParam("query", query).queryParam("format", "json")
                .queryParam("pageSize", limit).queryParam("resultType", "core")
                .build().encode().toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        List<ScientificArticleDto> result = new ArrayList<>();
        JsonNode items = root == null ? null : root.path("resultList").path("result");
        if (items == null || !items.isArray()) return result;
        for (JsonNode item : items) {
            ScientificArticleDto article = new ScientificArticleDto();
            article.setProvider("Europe PMC");
            String id = text(item, "id");
            String source = text(item, "source");
            article.setExternalId(source + ":" + id);
            article.setTitle(text(item, "title"));
            String authors = text(item, "authorString");
            article.setAuthors(authors.isBlank() ? List.of() : List.of(authors));
            article.setYear(parseInteger(text(item, "pubYear")));
            article.setVenue(text(item, "journalTitle"));
            article.setAbstractText(cleanHtml(text(item, "abstractText")));
            String doi = text(item, "doi");
            article.setUrl(!doi.isBlank() ? "https://doi.org/" + doi
                    : "https://europepmc.org/article/" + source + "/" + id);
            article.setOpenAccess(item.path("isOpenAccess").asBoolean(false));
            article.setCitationCount(integer(item, "citedByCount"));
            if (usable(article)) result.add(article);
        }
        return result;
    }

    private List<ScientificArticleDto> semanticScholar(String query, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl(
                        "https://api.semanticscholar.org/graph/v1/paper/search")
                .queryParam("query", query).queryParam("limit", limit)
                .queryParam("fields", "title,abstract,year,authors,url,openAccessPdf,venue,citationCount")
                .build().encode().toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        List<ScientificArticleDto> result = new ArrayList<>();
        JsonNode items = root == null ? null : root.path("data");
        if (items == null || !items.isArray()) return result;
        for (JsonNode item : items) {
            ScientificArticleDto article = new ScientificArticleDto();
            article.setProvider("Semantic Scholar");
            article.setExternalId(text(item, "paperId"));
            article.setTitle(text(item, "title"));
            article.setAuthors(StreamSupport.stream(item.path("authors").spliterator(), false)
                    .map(author -> text(author, "name")).filter(value -> !value.isBlank()).collect(Collectors.toList()));
            article.setYear(integer(item, "year"));
            article.setVenue(text(item, "venue"));
            article.setAbstractText(text(item, "abstract"));
            String openUrl = text(item.path("openAccessPdf"), "url");
            article.setUrl(openUrl.isBlank() ? text(item, "url") : openUrl);
            article.setOpenAccess(!openUrl.isBlank());
            article.setCitationCount(integer(item, "citationCount"));
            if (usable(article)) result.add(article);
        }
        return result;
    }

    private List<ScientificArticleDto> arxiv(String query, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl("https://export.arxiv.org/api/query")
                .queryParam("search_query", "all:\"" + query + "\"")
                .queryParam("start", 0).queryParam("max_results", limit)
                .queryParam("sortBy", "relevance").queryParam("sortOrder", "descending")
                .build().encode().toUri();
        String xml = restTemplate.getForObject(uri, String.class);
        if (xml == null || xml.isBlank()) return List.of();
        Document document = Jsoup.parse(xml, "", Parser.xmlParser());
        List<ScientificArticleDto> result = new ArrayList<>();
        for (Element entry : document.select("entry")) {
            ScientificArticleDto article = new ScientificArticleDto();
            article.setProvider("arXiv");
            article.setExternalId(entry.selectFirst("id") == null ? "" : entry.selectFirst("id").text());
            article.setTitle(entry.selectFirst("title") == null ? "" : entry.selectFirst("title").text());
            article.setAuthors(entry.select("author > name").stream().map(Element::text)
                    .filter(value -> !value.isBlank()).toList());
            String published = entry.selectFirst("published") == null ? "" : entry.selectFirst("published").text();
            article.setYear(published.length() >= 4 ? parseInteger(published.substring(0, 4)) : null);
            Element category = entry.selectFirst("category[term]");
            article.setVenue(category == null ? "arXiv" : category.attr("term"));
            article.setAbstractText(entry.selectFirst("summary") == null ? "" : entry.selectFirst("summary").text());
            Element pdf = entry.selectFirst("link[title=pdf]");
            Element alternate = entry.selectFirst("link[rel=alternate]");
            article.setUrl(pdf != null ? pdf.attr("href") : alternate == null ? article.getExternalId() : alternate.attr("href"));
            article.setOpenAccess(true);
            if (usable(article)) result.add(article);
        }
        return result;
    }

    private List<ScientificArticleDto> doaj(String query, int limit) {
        URI uri = UriComponentsBuilder.fromHttpUrl("https://doaj.org/api/search/articles")
                .pathSegment(query).queryParam("pageSize", limit).build().encode().toUri();
        JsonNode root = restTemplate.getForObject(uri, JsonNode.class);
        JsonNode items = root == null ? null : root.path("results");
        if (items == null || !items.isArray()) return List.of();
        List<ScientificArticleDto> result = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode metadata = item.path("bibjson");
            ScientificArticleDto article = new ScientificArticleDto();
            article.setProvider("DOAJ");
            article.setExternalId(text(item, "id"));
            article.setTitle(text(metadata, "title"));
            article.setAuthors(StreamSupport.stream(metadata.path("author").spliterator(), false)
                    .map(author -> text(author, "name")).filter(value -> !value.isBlank()).toList());
            article.setYear(integer(metadata, "year"));
            article.setVenue(text(metadata.path("journal"), "title"));
            article.setAbstractText(text(metadata, "abstract"));
            String link = StreamSupport.stream(metadata.path("link").spliterator(), false)
                    .map(node -> text(node, "url")).filter(value -> !value.isBlank()).findFirst().orElse("");
            if (link.isBlank() && !article.getExternalId().isBlank()) {
                link = "https://doaj.org/article/" + article.getExternalId();
            }
            article.setUrl(link);
            article.setOpenAccess(true);
            if (usable(article)) result.add(article);
        }
        return result;
    }

    private String expandQuery(String query, String category) {
        String suffix = switch (category == null ? "all" : category) {
            case "agriculture" -> " agriculture crop plant seed";
            case "biology" -> " biology genetics";
            case "medicine" -> " medicine clinical health";
            case "computer-science" -> " computer science artificial intelligence";
            case "engineering" -> " engineering technology";
            case "ecology" -> " ecology environment climate";
            case "social-science" -> " social science education";
            default -> "";
        };
        return query + suffix;
    }

    private Map<String, String> provider(String id, String name, String scope, String scopeEn, String description) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("id", id); map.put("name", name); map.put("scope", scope); map.put("scopeEn", scopeEn); map.put("description", description);
        return map;
    }

    private Map<String, String> category(String id, String name, String nameEn) { return Map.of("id", id, "name", name, "nameEn", nameEn); }

    private List<Map<String, String>> russianResources() {
        return List.of(
                resource("КиберЛенинка", "https://cyberleninka.ru/", "Статьи российских научных журналов в открытом доступе", "Open-access articles from Russian scientific journals"),
                resource("eLIBRARY.RU", "https://elibrary.ru/", "Научная электронная библиотека и Российский индекс научного цитирования", "Scientific digital library and Russian Science Citation Index"),
                resource("НЭБ", "https://rusneb.ru/", "Книги, диссертации и документы из российских библиотек", "Books, dissertations and documents from Russian libraries"),
                resource("РГБ", "https://opac.rsl.ru/", "Публичный каталог Российской государственной библиотеки", "Public catalogue of the Russian State Library"),
                resource("Math-Net.Ru", "https://www.mathnet.ru/", "Математика, физика, информатика и смежные науки", "Mathematics, physics, computer science and related disciplines"),
                resource("ИСТИНА МГУ", "https://istina.msu.ru/", "Публикации, проекты и научные отчёты Московского университета", "Publications, projects and research reports from Moscow State University"),
                resource("Научный портал СПбГУ", "https://pureportal.spbu.ru/ru/", "Публикации и результаты исследований Санкт-Петербургского университета", "Research outputs from Saint Petersburg State University"),
                resource("Публикации ВШЭ", "https://publications.hse.ru/", "Статьи, книги, препринты и главы сотрудников НИУ ВШЭ", "Articles, books, preprints and chapters by HSE researchers"),
                resource("Президентская библиотека", "https://www.prlib.ru/", "Цифровые коллекции по истории, праву и российской государственности", "Digital collections on history, law and Russian statehood"),
                resource("ГПНТБ России", "https://www.gpntb.ru/", "Научно-техническая литература, каталоги и электронные ресурсы", "Scientific and technical literature, catalogues and digital resources"),
                resource("ГПНТБ СО РАН", "https://www.spsl.nsc.ru/", "Каталоги и электронные ресурсы Сибирского отделения РАН", "Catalogues and digital resources of the Siberian Branch of RAS"),
                resource("НЭИКОН", "https://neicon.ru/", "Российские научные журналы, репозитории и информационные ресурсы", "Russian scientific journals, repositories and information resources"),
                resource("Электронная библиотека ГПИБ", "https://elib.shpl.ru/", "Исторические источники, книги и периодические издания", "Historical sources, books and periodicals"),
                resource("Объединённый институт ядерных исследований", "https://lib.jinr.ru/", "Каталог библиотеки и публикации по физике и ядерным исследованиям", "Library catalogue and publications on physics and nuclear research"),
                resource("ВИНИТИ РАН", "https://www.viniti.ru/", "Научно-техническая информация и реферативные издания", "Scientific and technical information and abstract journals")
        );
    }

    private Map<String, String> resource(String name, String url, String description, String descriptionEn) {
        return Map.of("name", name, "url", url, "description", description, "descriptionEn", descriptionEn);
    }
    private boolean usable(ScientificArticleDto article) { return article.getTitle() != null && !article.getTitle().isBlank() && article.getUrl() != null && !article.getUrl().isBlank(); }
    private int citationCount(ScientificArticleDto article) { return article.getCitationCount() == null ? -1 : article.getCitationCount(); }
    private String text(JsonNode node, String field) { return node == null || node.path(field).isMissingNode() || node.path(field).isNull() ? "" : node.path(field).asText(""); }
    private String firstText(JsonNode node) { return node != null && node.isArray() && !node.isEmpty() ? node.get(0).asText("") : ""; }
    private Integer integer(JsonNode node, String field) { return node != null && node.path(field).canConvertToInt() ? node.path(field).asInt() : null; }
    private Integer parseInteger(String value) { try { return Integer.valueOf(value); } catch (Exception ignored) { return null; } }
    private Integer year(JsonNode published) { return published.path("date-parts").isArray() && !published.path("date-parts").isEmpty() && !published.path("date-parts").get(0).isEmpty() ? published.path("date-parts").get(0).get(0).asInt() : null; }
    private String cleanHtml(String value) { return value == null || value.isBlank() ? "" : Jsoup.parse(value).text(); }

    private record CachedSearch(Instant createdAt, List<ScientificArticleDto> articles) {
    }
}
