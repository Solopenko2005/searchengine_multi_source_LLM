package searchengine.services.assistant;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import searchengine.config.assistant.AssistantConfig;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Local persistent HNSW index. PostgreSQL remains the source of truth. */
@Slf4j
@org.springframework.stereotype.Service
public class LocalVectorIndexService {
    private static final String ID = "chunk_id";
    private static final String SITE = "site_id";
    private static final String VECTOR = "embedding";

    private final AssistantConfig config;
    private Directory directory;
    private IndexWriter writer;
    private DirectoryReader reader;

    public LocalVectorIndexService(AssistantConfig config) {
        this.config = config;
    }

    @PostConstruct
    synchronized void initialize() {
        try {
            Path path = Path.of(config.getEmbedding().getIndexPath()).toAbsolutePath().normalize();
            Files.createDirectories(path);
            directory = FSDirectory.open(path);
            IndexWriterConfig writerConfig = new IndexWriterConfig(new KeywordAnalyzer());
            writerConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            writer = new IndexWriter(directory, writerConfig);
            reader = DirectoryReader.open(writer);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось открыть локальный векторный индекс", exception);
        }
    }

    public synchronized void reset() {
        try {
            writer.deleteAll();
            commitAndRefresh();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось очистить векторный индекс", exception);
        }
    }

    public synchronized void upsertAll(List<VectorDocument> vectors) {
        if (vectors == null || vectors.isEmpty()) return;
        try {
            for (VectorDocument value : vectors) {
                if (value.vector() == null || value.vector().length == 0) continue;
                Document document = new Document();
                String id = String.valueOf(value.chunkId());
                document.add(new StringField(ID, id, Field.Store.YES));
                document.add(new StringField(SITE, String.valueOf(value.siteId()), Field.Store.NO));
                document.add(new StoredField("page_id", value.pageId()));
                document.add(new KnnFloatVectorField(VECTOR, value.vector(), VectorSimilarityFunction.COSINE));
                writer.updateDocument(new Term(ID, id), document);
            }
            commitAndRefresh();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось обновить векторный индекс", exception);
        }
    }

    public synchronized List<VectorHit> search(float[] queryVector, Collection<Integer> siteIds, int limit) {
        if (queryVector == null || queryVector.length == 0 || siteIds == null || siteIds.isEmpty()) return List.of();
        try {
            refreshReader();
            List<BytesRef> terms = siteIds.stream().map(String::valueOf).map(BytesRef::new).toList();
            KnnFloatVectorQuery query = new KnnFloatVectorQuery(VECTOR, queryVector,
                    Math.max(1, limit), new TermInSetQuery(SITE, terms));
            IndexSearcher searcher = new IndexSearcher(reader);
            ScoreDoc[] hits = searcher.search(query, Math.max(1, limit)).scoreDocs;
            java.util.ArrayList<VectorHit> result = new java.util.ArrayList<>(hits.length);
            for (ScoreDoc hit : hits) {
                Document document = searcher.storedFields().document(hit.doc);
                result.add(new VectorHit(Long.parseLong(document.get(ID)), hit.score));
            }
            return result;
        } catch (IllegalArgumentException exception) {
            log.warn("Размерность embedding-модели изменилась; векторный поиск временно пропущен: {}",
                    exception.getMessage());
            return List.of();
        } catch (IOException exception) {
            throw new IllegalStateException("Ошибка смыслового поиска", exception);
        }
    }

    private void commitAndRefresh() throws IOException {
        writer.commit();
        refreshReader();
    }

    private void refreshReader() throws IOException {
        DirectoryReader changed = DirectoryReader.openIfChanged(reader, writer);
        if (changed != null) {
            reader.close();
            reader = changed;
        }
    }

    @PreDestroy
    synchronized void close() {
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (directory != null) directory.close();
        } catch (IOException exception) {
            log.warn("Не удалось корректно закрыть векторный индекс: {}", exception.getMessage());
        }
    }

    public record VectorDocument(long chunkId, int pageId, int siteId, float[] vector) {
    }

    public record VectorHit(long chunkId, float score) {
    }
}
