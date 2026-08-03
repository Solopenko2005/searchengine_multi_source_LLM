package searchengine.services;

import org.junit.jupiter.api.Test;
import searchengine.config.IndexingState;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repository.SiteRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexingStatusServiceTest {

    @Test
    void readyOnlyWhenEverySourceIsIndexedAndNoTaskRuns() {
        SiteRepository repository = mock(SiteRepository.class);
        Site first = new Site();
        first.setStatus(Status.INDEXED);
        Site second = new Site();
        second.setStatus(Status.INDEXED);
        when(repository.findAll()).thenReturn(List.of(first, second));

        IndexingStatusService service = new IndexingStatusService(new IndexingState(), repository);
        Map<String, Object> status = service.getStatus();

        assertThat(status.get("ready")).isEqualTo(true);
        assertThat(status.get("indexedSources")).isEqualTo(2L);
    }

    @Test
    void stoppedSourceMakesIndexIncomplete() {
        SiteRepository repository = mock(SiteRepository.class);
        Site source = new Site();
        source.setStatus(Status.STOPPED);
        when(repository.findAll()).thenReturn(List.of(source));

        IndexingStatusService service = new IndexingStatusService(new IndexingState(), repository);
        Map<String, Object> status = service.getStatus();

        assertThat(status.get("ready")).isEqualTo(false);
        assertThat(status.get("stoppedSources")).isEqualTo(1L);
    }
}
