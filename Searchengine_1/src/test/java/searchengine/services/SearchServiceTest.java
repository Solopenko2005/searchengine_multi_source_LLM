package searchengine.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import searchengine.dto.response.SearchResponse;
import searchengine.model.Lemma;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SearchServiceTest {
    private Lemmatizer lemmatizer;
    private LemmaRepository lemmas;
    private IndexRepository indexes;
    private PageRepository pages;
    private SiteRepository sites;
    private CurrentUserService currentUser;
    private SearchService service;

    @BeforeEach
    void setUp() {
        lemmatizer = mock(Lemmatizer.class);
        lemmas = mock(LemmaRepository.class);
        indexes = mock(IndexRepository.class);
        pages = mock(PageRepository.class);
        sites = mock(SiteRepository.class);
        currentUser = mock(CurrentUserService.class);
        service = new SearchService(lemmatizer, lemmas, indexes, pages, sites, currentUser);
    }

    @Test
    void rejectsBlankQueryBeforeDatabaseAccess() {
        SearchResponse response = service.search("  ", null, 0, 10);

        assertFalse(response.isResult());
        assertEquals("Задан пустой поисковый запрос", response.getError());
        verifyNoInteractions(lemmas, indexes, pages, sites, currentUser);
    }

    @Test
    void returnsOnlyPagesAvailableToCurrentWorkspace() {
        Page page = fixturePage();
        Lemma lemma = fixtureLemma(page.getSite());
        when(lemmatizer.getLemmas("искусственный интеллект"))
                .thenReturn(new LinkedHashMap<>(Map.of("интеллект", 1)));
        when(pages.count()).thenReturn(20L);
        when(lemmas.findAllByLemma("интеллект")).thenReturn(List.of(lemma));
        when(indexes.findPagesByLemmas(List.of("интеллект"), 1L)).thenReturn(List.of(page));
        when(currentUser.canAccess(page)).thenReturn(false);

        SearchResponse response = service.search("искусственный интеллект", null, 0, 10);

        assertTrue(response.isResult());
        assertEquals(0, response.getCount());
        assertTrue(response.getData().isEmpty());
        verify(indexes, never()).sumRanksByPagesAndLemmas(anyList(), anyList());
    }

    @Test
    void ranksResultAndBuildsHighlightedSafeSnippet() {
        Page page = fixturePage();
        Lemma lemma = fixtureLemma(page.getSite());
        when(lemmatizer.getLemmas("искусственный интеллект"))
                .thenReturn(new LinkedHashMap<>(Map.of("интеллект", 1)));
        when(pages.count()).thenReturn(20L);
        when(lemmas.findAllByLemma("интеллект")).thenReturn(List.of(lemma));
        when(indexes.findPagesByLemmas(List.of("интеллект"), 1L)).thenReturn(List.of(page));
        when(currentUser.canAccess(page)).thenReturn(true);
        when(indexes.sumRanksByPagesAndLemmas(eq(List.of(page)), eq(List.of("интеллект"))))
                .thenReturn(List.<Object[]>of(new Object[]{page.getId(), 7.5d}));
        when(lemmatizer.getWordLemmas(anyString())).thenAnswer(invocation -> {
            String word = invocation.getArgument(0, String.class).toLowerCase(Locale.ROOT);
            return word.startsWith("интеллект") ? List.of("интеллект") : List.of();
        });

        SearchResponse response = service.search("искусственный интеллект", null, 0, 10);

        assertTrue(response.isResult());
        assertEquals(1, response.getCount());
        assertEquals(1, response.getData().size());
        assertEquals("Научная статья", response.getData().get(0).getTitle());
        assertEquals(1.0d, response.getData().get(0).getRelevance());
        assertTrue(response.getData().get(0).getSnippet().contains("<b>интеллект</b>"));
        assertFalse(response.getData().get(0).getSnippet().contains("<script>"));
        assertEquals("https://example.org/articles/1", response.getData().get(0).getUrl());
    }

    private Site fixtureSite() {
        Site site = new Site();
        site.setId(1);
        site.setUrl("https://example.org");
        site.setName("Научный источник");
        site.setOwnerId("researcher@example.org");
        site.setSourceType(SourceType.WEBSITE);
        return site;
    }

    private Page fixturePage() {
        Page page = new Page();
        page.setId(42);
        page.setSite(fixtureSite());
        page.setPath("/articles/1");
        page.setCode(200);
        page.setContent("<html><head><title>Научная статья</title></head>"
                + "<body><script>alert('x')</script>Искусственный интеллект помогает исследователю.</body></html>");
        return page;
    }

    private Lemma fixtureLemma(Site site) {
        Lemma lemma = new Lemma();
        lemma.setId(5);
        lemma.setSite(site);
        lemma.setLemma("интеллект");
        lemma.setFrequency(2);
        return lemma;
    }
}
