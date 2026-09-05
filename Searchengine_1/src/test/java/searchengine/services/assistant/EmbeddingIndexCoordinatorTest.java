package searchengine.services.assistant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import searchengine.config.assistant.AssistantConfig;
import searchengine.model.AssistantChunk;
import searchengine.model.AssistantChunkStatus;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.repository.AssistantChunkRepository;
import searchengine.repository.PageRepository;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingIndexCoordinatorTest {

    @Test
    void reusesCompletePersistentVectorIndexOnStartup() {
        AssistantConfig config = new AssistantConfig();
        PageRepository pages = mock(PageRepository.class);
        AssistantChunkRepository chunks = mock(AssistantChunkRepository.class);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        LocalVectorIndexService vectorIndex = mock(LocalVectorIndexService.class);
        when(chunks.countByStatus(AssistantChunkStatus.READY)).thenReturn(44_901L);
        when(vectorIndex.documentCount()).thenReturn(44_901);
        when(chunks.findByStatusOrderByIdAsc(any(), any(Pageable.class))).thenReturn(List.of());
        when(pages.findIdsWithoutAssistantChunks(any(Pageable.class))).thenReturn(List.of());
        EmbeddingIndexCoordinator coordinator = new EmbeddingIndexCoordinator(config, pages, chunks,
                new TextChunker(config), embeddings, vectorIndex, Runnable::run);

        coordinator.onReady();

        verify(vectorIndex, never()).reset();
        verify(vectorIndex, never()).upsertAll(any());
    }

    @Test
    void marksEmptyPageAsSkippedSoItCannotStarveTheQueue() {
        AssistantConfig config = new AssistantConfig();
        PageRepository pages = mock(PageRepository.class);
        AssistantChunkRepository chunks = mock(AssistantChunkRepository.class);
        TextChunker chunker = new TextChunker(config);
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        LocalVectorIndexService vectorIndex = mock(LocalVectorIndexService.class);
        Executor directExecutor = Runnable::run;

        Page page = new Page();
        page.setId(7);
        page.setCode(200);
        page.setContent("<html><body>   </body></html>");
        Site site = new Site();
        site.setId(3);
        site.setOwnerId("owner@example.test");
        page.setSite(site);

        when(chunks.findByStatusOrderByIdAsc(any(), any(Pageable.class))).thenReturn(List.of());
        when(pages.findIdsWithoutAssistantChunks(any(Pageable.class))).thenReturn(List.of(7));
        when(pages.findAllById(List.of(7))).thenReturn(List.of(page));
        when(chunks.saveAll(any())).thenAnswer(invocation ->
                StreamSupport.stream(((Iterable<AssistantChunk>) invocation.getArgument(0)).spliterator(), false)
                        .toList());

        EmbeddingIndexCoordinator coordinator = new EmbeddingIndexCoordinator(config, pages, chunks,
                chunker, embeddings, vectorIndex, directExecutor);
        coordinator.scanNow();

        ArgumentCaptor<Iterable<AssistantChunk>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(chunks).saveAll(captor.capture());
        List<AssistantChunk> saved = StreamSupport.stream(captor.getValue().spliterator(), false).toList();
        assertThat(saved).singleElement().satisfies(marker -> {
            assertThat(marker.getStatus()).isEqualTo(AssistantChunkStatus.SKIPPED);
            assertThat(marker.getContent()).isEmpty();
            assertThat(marker.getPage().getId()).isEqualTo(7);
        });
        verify(embeddings, never()).embedDocuments(any());
    }
}
