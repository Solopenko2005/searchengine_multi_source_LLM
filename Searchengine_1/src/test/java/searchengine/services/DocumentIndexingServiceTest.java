package searchengine.services;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SourceType;
import searchengine.model.Status;
import searchengine.model.Topic;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;
import searchengine.repository.TopicRepository;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexingServiceTest {
    private SiteRepository sites;
    private PageRepository pages;
    private DatabaseService database;
    private Lemmatizer lemmatizer;
    private TopicExtractorService topicExtractor;
    private TopicRepository topics;
    private IndexingJobService jobs;
    private DocumentIndexingService service;

    @BeforeEach
    void setUp() {
        sites = mock(SiteRepository.class);
        pages = mock(PageRepository.class);
        database = mock(DatabaseService.class);
        lemmatizer = mock(Lemmatizer.class);
        topicExtractor = mock(TopicExtractorService.class);
        topics = mock(TopicRepository.class);
        jobs = mock(IndexingJobService.class);
        CurrentUserService users = mock(CurrentUserService.class);
        when(users.getUserId()).thenReturn("alice");
        service = new DocumentIndexingService(sites, pages, database, lemmatizer, topicExtractor,
                topics, mock(PageProcessor.class), users, jobs);
        ReflectionTestUtils.setField(service, "documentIndexingEnabled", true);
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 10_000_000L);
    }

    @AfterEach
    void shutdown() {
        service.shutdownExecutor();
    }

    @Test
    void indexesDocxIntoSearchAndTopicStores() throws Exception {
        Site persistedSite = new Site();
        persistedSite.setId(7);
        persistedSite.setOwnerId("alice");
        persistedSite.setName("research.docx");
        persistedSite.setSourceType(SourceType.DOCUMENT);
        when(sites.findByUrl(any())).thenReturn(Optional.empty());
        when(sites.save(any(Site.class))).thenAnswer(invocation -> {
            Site candidate = invocation.getArgument(0);
            if (candidate.getId() == 0) candidate.setId(7);
            return candidate;
        });
        when(pages.findBySiteAndPathAndOwnerId(7, "/document", "alice")).thenReturn(Optional.empty());
        org.mockito.Mockito.doAnswer(invocation -> {
            Page page = invocation.getArgument(0);
            page.setId(42);
            return null;
        }).when(database).savePage(any(Page.class));
        when(lemmatizer.extractLemmasWithRank(any())).thenReturn(Map.of("урожайность", 2));
        Topic topic = new Topic();
        topic.setContent("Прогнозирование урожайности");
        when(topicExtractor.extractTopics(any(Page.class), any())).thenReturn(List.of(topic));

        Map<String, Object> response = service.indexDocuments(new MockMultipartFile[]{docx()});

        assertThat(response).containsEntry("result", true).containsEntry("success", 1)
                .containsEntry("failed", 0);
        verify(database).savePageSearchIndex(any(Page.class), any(Site.class),
                org.mockito.ArgumentMatchers.eq(Map.of("урожайность", 2)));
        verify(topics).save(topic);
        assertThat(topic.getPage().getId()).isEqualTo(42);
        assertThat(topic.getSite().getId()).isEqualTo(7);
        assertThat(((List<Map<String, Object>>) response.get("files")).get(0))
                .containsEntry("topics", 1).containsEntry("pageId", 42);
        verify(sites, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.argThat(
                site -> site.getStatus() == Status.INDEXED));
    }

    @Test
    void rejectsMissingEmptyUnsupportedAndOversizedUploads() {
        assertThat(service.indexDocuments(null)).containsEntry("result", false);
        assertThat(service.submitDocuments(new MockMultipartFile[0])).containsEntry("result", false);
        MockMultipartFile empty = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);
        assertThat(service.submitDocuments(new MockMultipartFile[]{empty}).get("error").toString())
                .contains("пуст");
        MockMultipartFile text = new MockMultipartFile("files", "notes.txt", "text/plain", "x".getBytes());
        assertThat(service.indexDocuments(new MockMultipartFile[]{text}).get("failed")).isEqualTo(1);
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 1L);
        MockMultipartFile large = new MockMultipartFile("files", "large.pdf", "application/pdf", new byte[]{1, 2});
        assertThat(service.submitDocuments(new MockMultipartFile[]{large}).get("error").toString())
                .contains("размер");
    }

    @Test
    void exposesStatusAndStopResultFromDurableJobs() {
        when(jobs.listVisible()).thenReturn(List.of(
                Map.of("sourceType", SourceType.DOCUMENT, "canStop", true, "partialSearchReady", true),
                Map.of("sourceType", SourceType.WEBSITE, "canStop", true, "partialSearchReady", true)));
        when(jobs.requestStopAllVisible(SourceType.DOCUMENT)).thenReturn(1);

        assertThat(service.currentUserStatus()).containsEntry("inProgress", true)
                .containsEntry("indexedDocuments", 1L).containsEntry("total", 1);
        assertThat(service.stopCurrentUserJob()).containsEntry("result", true).containsEntry("stopped", 1);
    }

    private MockMultipartFile docx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(
                    "Исследование методов машинного обучения для прогнозирования урожайности пшеницы.");
            document.write(output);
        }
        return new MockMultipartFile("files", "research.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", output.toByteArray());
    }
}
