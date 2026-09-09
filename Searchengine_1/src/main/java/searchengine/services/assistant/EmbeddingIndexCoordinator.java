package searchengine.services.assistant;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.assistant.AssistantConfig;
import searchengine.model.AssistantChunk;
import searchengine.model.AssistantChunkStatus;
import searchengine.model.Page;
import searchengine.repository.AssistantChunkRepository;
import searchengine.repository.PageRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class EmbeddingIndexCoordinator {
    private final AssistantConfig config;
    private final PageRepository pageRepository;
    private final AssistantChunkRepository chunkRepository;
    private final TextChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final LocalVectorIndexService vectorIndex;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger interactiveRequests = new AtomicInteger();
    private final AtomicLong lastFailedRetryAtMillis = new AtomicLong();

    public EmbeddingIndexCoordinator(AssistantConfig config, PageRepository pageRepository,
                                     AssistantChunkRepository chunkRepository, TextChunker chunker,
                                     EmbeddingClient embeddingClient, LocalVectorIndexService vectorIndex,
                                     @Qualifier("assistantIndexExecutor") Executor executor) {
        this.config = config;
        this.pageRepository = pageRepository;
        this.chunkRepository = chunkRepository;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.vectorIndex = vectorIndex;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.execute(() -> {
            rebuildVectorIndex();
            scanNow();
        });
    }

    @Scheduled(fixedDelayString = "${assistant.embedding.scan-delay-millis:5000}")
    public void scheduledScan() {
        if (!workPaused() && !running.get()) executor.execute(this::scanNow);
    }

    public void scanNow() {
        if (workPaused() || !config.getEmbedding().isEnabled() || !running.compareAndSet(false, true)) return;
        try {
            int batch = Math.max(1, config.getEmbedding().getScanBatchSize());
            List<AssistantChunk> pending = chunkRepository.findByStatusOrderByIdAsc(
                    AssistantChunkStatus.PENDING, PageRequest.of(0, batch * 8));
            if (!pending.isEmpty()) {
                embedChunks(pending);
                return;
            }
            List<Integer> pageIds = pageRepository.findIdsWithoutAssistantChunks(PageRequest.of(0, batch));
            if (!pageIds.isEmpty()) {
                embedChunks(createChunks(pageIds));
                return;
            }
            retryFailedAfterCooldown(batch);
        } catch (Exception exception) {
            log.warn("Фоновая смысловая индексация временно недоступна: {}", exception.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void retryFailedAfterCooldown(int batch) {
        if (!config.getEmbedding().isAutoRetryFailed()
                || chunkRepository.countByStatus(AssistantChunkStatus.FAILED) == 0) return;
        long now = System.currentTimeMillis();
        long delayMillis = Math.max(1, config.getEmbedding().getFailedRetryDelaySeconds()) * 1000L;
        long previous = lastFailedRetryAtMillis.get();
        if (previous > 0 && now - previous < delayMillis) return;
        if (!lastFailedRetryAtMillis.compareAndSet(previous, now)) return;
        int reset = chunkRepository.resetFailed(AssistantChunkStatus.FAILED, AssistantChunkStatus.PENDING);
        if (reset <= 0) return;
        log.info("Автоматически возвращено в смысловую индексацию {} фрагментов", reset);
        List<AssistantChunk> retryBatch = chunkRepository.findByStatusOrderByIdAsc(
                AssistantChunkStatus.PENDING, PageRequest.of(0, Math.max(1, batch * 8)));
        if (!retryBatch.isEmpty()) embedChunks(retryBatch);
    }

    private List<AssistantChunk> createChunks(List<Integer> pageIds) {
        List<AssistantChunk> chunks = new ArrayList<>();
        for (Page page : pageRepository.findAllById(pageIds)) {
            if (workPaused()) break;
            if (page.getSite() == null || (page.getCode() != null && page.getCode() >= 400)) continue;
            String plainText = page.getContent() == null ? "" : Jsoup.parse(page.getContent()).text();
            List<String> parts = chunker.split(plainText);
            if (parts.isEmpty()) {
                // Keep a durable marker for empty/technical pages. Without it the same
                // first page batch is selected forever and later pages starve.
                chunks.add(newChunk(page, 0, "", AssistantChunkStatus.SKIPPED));
                continue;
            }
            for (int i = 0; i < parts.size(); i++) {
                chunks.add(newChunk(page, i, parts.get(i), AssistantChunkStatus.PENDING));
            }
        }
        if (chunks.isEmpty()) return List.of();
        return chunkRepository.saveAll(chunks).stream()
                .filter(chunk -> chunk.getStatus() == AssistantChunkStatus.PENDING)
                .toList();
    }

    private AssistantChunk newChunk(Page page, int index, String content, AssistantChunkStatus status) {
        AssistantChunk chunk = new AssistantChunk();
        chunk.setPage(page);
        chunk.setSiteId(page.getSite().getId());
        chunk.setOwnerId(page.getOwnerId() == null ? page.getSite().getOwnerId() : page.getOwnerId());
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setContentHash(sha256(content));
        chunk.setStatus(status);
        chunk.setUpdatedAt(LocalDateTime.now());
        return chunk;
    }

    private void embedChunks(List<AssistantChunk> chunks) {
        int batchSize = Math.max(1, config.getEmbedding().getBatchSize());
        for (int from = 0; from < chunks.size() && !workPaused(); from += batchSize) {
            List<AssistantChunk> batch = chunks.subList(from, Math.min(chunks.size(), from + batchSize));
            try {
                List<float[]> vectors = embeddingClient.embedDocuments(
                        batch.stream().map(AssistantChunk::getContent).toList());
                List<LocalVectorIndexService.VectorDocument> indexDocuments = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    AssistantChunk chunk = batch.get(i);
                    float[] vector = vectors.get(i);
                    chunk.setEmbedding(EmbeddingCodec.encode(vector));
                    chunk.setEmbeddingModel(embeddingClient.model());
                    chunk.setStatus(AssistantChunkStatus.READY);
                    chunk.setErrorMessage(null);
                    chunk.setUpdatedAt(LocalDateTime.now());
                    indexDocuments.add(new LocalVectorIndexService.VectorDocument(chunk.getId(),
                            chunk.getPage().getId(), chunk.getSiteId(), vector));
                }
                chunkRepository.saveAll(batch);
                vectorIndex.upsertAll(indexDocuments);
            } catch (Exception exception) {
                String error = truncate(exception.getMessage(), 1000);
                for (AssistantChunk chunk : batch) {
                    chunk.setStatus(AssistantChunkStatus.FAILED);
                    chunk.setErrorMessage(error);
                    chunk.setUpdatedAt(LocalDateTime.now());
                }
                chunkRepository.saveAll(batch);
                log.warn("Не удалось построить embeddings для {} фрагментов: {}", batch.size(), error);
            }
        }
    }

    private void rebuildVectorIndex() {
        if (!config.getEmbedding().isEnabled()) return;
        try {
            long readyChunks = chunkRepository.countByStatus(AssistantChunkStatus.READY);
            int indexedChunks = vectorIndex.documentCount();
            if (readyChunks > 0 && readyChunks == indexedChunks) {
                log.info("Используется сохранённый векторный индекс: {} фрагментов", indexedChunks);
                return;
            }
            log.info("Восстановление векторного индекса: в базе {}, на диске {} фрагментов",
                    readyChunks, indexedChunks);
            vectorIndex.reset();
            int page = 0;
            int size = 500;
            while (true) {
                List<AssistantChunk> chunks = chunkRepository.findByStatusWithPage(
                        AssistantChunkStatus.READY, PageRequest.of(page++, size));
                if (chunks.isEmpty()) break;
                List<LocalVectorIndexService.VectorDocument> vectors = chunks.stream()
                        .map(chunk -> new LocalVectorIndexService.VectorDocument(chunk.getId(),
                                chunk.getPage().getId(), chunk.getSiteId(),
                                EmbeddingCodec.decode(chunk.getEmbedding())))
                        .filter(value -> value.vector().length > 0).toList();
                vectorIndex.upsertAll(vectors);
                if (chunks.size() < size) break;
            }
        } catch (Exception exception) {
            log.warn("Векторный индекс будет восстановлен позже: {}", exception.getMessage());
        }
    }

    public Map<String, Object> status(Collection<Integer> sourceIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", true);
        result.put("enabled", config.getEmbedding().isEnabled());
        result.put("configured", embeddingClient.isConfigured());
        result.put("model", embeddingClient.model());
        result.put("running", running.get());
        result.put("paused", paused.get());
        result.put("prioritizingAssistant", interactiveRequests.get() > 0);
        if (sourceIds == null || sourceIds.isEmpty()) {
            result.put("pages", 0L); result.put("processedPages", 0L);
            result.put("chunks", 0L); result.put("readyChunks", 0L); result.put("skippedPages", 0L);
            result.put("failedChunks", 0L);
            result.put("progress", 100);
            return result;
        }
        long pages = pageRepository.countAssistantIndexableBySiteIds(sourceIds);
        long processedPages = chunkRepository.countDistinctPagesBySiteIdsAndStatusIn(
                sourceIds, List.of(AssistantChunkStatus.READY, AssistantChunkStatus.SKIPPED));
        long chunks = chunkRepository.countBySiteIdIn(sourceIds);
        long ready = chunkRepository.countBySiteIdInAndStatus(sourceIds, AssistantChunkStatus.READY);
        long skipped = chunkRepository.countBySiteIdInAndStatus(sourceIds, AssistantChunkStatus.SKIPPED);
        long failed = chunkRepository.countBySiteIdInAndStatus(sourceIds, AssistantChunkStatus.FAILED);
        long pending = chunkRepository.countBySiteIdInAndStatus(sourceIds, AssistantChunkStatus.PENDING);
        result.put("pages", pages);
        result.put("processedPages", processedPages);
        result.put("chunks", chunks);
        result.put("readyChunks", ready);
        result.put("skippedPages", skipped);
        result.put("failedChunks", failed);
        result.put("pendingChunks", pending);
        result.put("autoRetryFailed", config.getEmbedding().isAutoRetryFailed());
        result.put("searchReady", ready > 0);
        result.put("progress", pages == 0 ? 100 : Math.min(100, Math.round(processedPages * 100f / pages)));
        return result;
    }

    public void pause() { paused.set(true); }
    public void resume() { paused.set(false); scheduledScan(); }

    public void beginInteractiveRequest() {
        interactiveRequests.incrementAndGet();
    }

    public void endInteractiveRequest() {
        int remaining = interactiveRequests.updateAndGet(value -> Math.max(0, value - 1));
        if (remaining == 0 && !paused.get()) scheduledScan();
    }

    private boolean workPaused() {
        return paused.get() || interactiveRequests.get() > 0;
    }

    @Transactional
    public int retryFailed() {
        int reset = chunkRepository.resetFailed(AssistantChunkStatus.FAILED, AssistantChunkStatus.PENDING);
        resume();
        return reset;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String truncate(String value, int limit) {
        if (value == null || value.isBlank()) return "Неизвестная ошибка embedding-модели";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
