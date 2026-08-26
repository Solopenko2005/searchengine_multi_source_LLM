package searchengine.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import searchengine.model.Site;
import searchengine.repository.SiteRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SourceDeletionServiceTest {
    private SiteRepository sites;
    private JdbcTemplate jdbc;
    private IndexingJobService jobs;
    private CurrentUserService currentUser;
    private SourceDeletionService service;
    private Site site;

    @BeforeEach
    void setUp() {
        sites = mock(SiteRepository.class);
        jdbc = mock(JdbcTemplate.class);
        jobs = mock(IndexingJobService.class);
        currentUser = mock(CurrentUserService.class);
        service = new SourceDeletionService(sites, jdbc, jobs, currentUser);
        site = new Site();
        site.setId(42);
        site.setName("Research source");
        site.setUrl("https://example.org");
        when(currentUser.isAdmin()).thenReturn(true);
        when(sites.findById(42)).thenReturn(Optional.of(site));
    }

    @Test
    void removesSourceAndAllSearchData() {
        var result = service.delete(List.of(42));

        assertEquals(1, result.get("deleted"));
        verify(jdbc, times(6)).update(anyString(), eq(42));
    }

    @Test
    void activeSourceMustBeStoppedBeforeDeletion() {
        when(jobs.hasActiveSource(site.getUrl())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.delete(List.of(42)));
        verifyNoInteractions(jdbc);
    }
}
