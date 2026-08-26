package searchengine.services;

import org.springframework.stereotype.Service;
import searchengine.model.IndexingJob;
import searchengine.model.IndexingJobState;
import searchengine.model.SourceType;
import searchengine.repository.IndexingJobRepository;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class IndexingJobService {
    private static final Set<IndexingJobState> ACTIVE = EnumSet.of(
            IndexingJobState.QUEUED, IndexingJobState.RUNNING, IndexingJobState.STOPPING);

    private final IndexingJobRepository repository;
    private final CurrentUserService currentUserService;
    private final Map<String, RuntimeJob> runtimeJobs = new ConcurrentHashMap<>();

    public IndexingJobService(IndexingJobRepository repository, CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    @PostConstruct
    void closeInterruptedJobs() {
        for (IndexingJob job : repository.findByStateIn(ACTIVE)) {
            job.setState(IndexingJobState.STOPPED);
            job.setStage("Прервано перезапуском приложения");
            job.setCancelRequested(true);
            job.setFinishedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            repository.save(job);
        }
    }

    public IndexingJob create(String ownerId, SourceType type, String name, String url, int expectedItems) {
        LocalDateTime now = LocalDateTime.now();
        IndexingJob job = new IndexingJob();
        job.setId(UUID.randomUUID().toString());
        job.setOwnerId(ownerId == null || ownerId.isBlank() ? "system" : ownerId);
        job.setSourceType(type);
        job.setSourceName(name == null || name.isBlank() ? url : name);
        job.setSourceUrl(url);
        job.setState(IndexingJobState.QUEUED);
        job.setStage("Ожидает запуска");
        job.setDiscoveredItems(Math.max(0, expectedItems));
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        repository.save(job);
        runtimeJobs.put(job.getId(), new RuntimeJob(expectedItems));
        return job;
    }

    public boolean hasActiveSource(String sourceUrl) {
        return repository.existsBySourceUrlAndStateIn(sourceUrl, ACTIVE);
    }

    public void attachFuture(String jobId, Future<?> future) {
        RuntimeJob runtime = runtimeJobs.get(jobId);
        if (runtime != null) runtime.future = future;
    }

    public void markRunning(String jobId, Integer siteId, String stage) {
        mutate(jobId, job -> {
            job.setState(IndexingJobState.RUNNING);
            job.setSiteId(siteId);
            job.setStage(stage);
            job.setStartedAt(LocalDateTime.now());
        });
    }

    public void updateStage(String jobId, String stage, int progress) {
        mutate(jobId, job -> {
            job.setStage(stage);
            job.setProgressPercent(Math.max(job.getProgressPercent(), Math.min(99, Math.max(0, progress))));
        });
    }

    public void recordDiscovered(String jobId) {
        RuntimeJob runtime = runtimeJobs.get(jobId);
        if (runtime == null) return;
        runtime.discovered.incrementAndGet();
        maybePersistCounters(jobId, runtime);
    }

    public void recordProcessed(String jobId, boolean searchable) {
        RuntimeJob runtime = runtimeJobs.get(jobId);
        if (runtime == null) return;
        runtime.processed.incrementAndGet();
        if (searchable) runtime.searchable.incrementAndGet();
        else runtime.failed.incrementAndGet();
        maybePersistCounters(jobId, runtime);
    }

    private void maybePersistCounters(String jobId, RuntimeJob runtime) {
        int processed = runtime.processed.get();
        long now = System.nanoTime();
        long last = runtime.lastPersistedNanos.get();
        boolean checkpoint = processed <= 1 || processed % 10 == 0 || now - last >= 1_000_000_000L;
        if (checkpoint && runtime.lastPersistedNanos.compareAndSet(last, now)) {
            persistCounters(jobId, runtime);
        }
    }

    private void persistCounters(String jobId, RuntimeJob runtime) {
        mutate(jobId, job -> {
            int discovered = Math.max(runtime.discovered.get(), runtime.processed.get());
            int processed = runtime.processed.get();
            job.setDiscoveredItems(discovered);
            job.setProcessedItems(processed);
            job.setFailedItems(runtime.failed.get());
            job.setSearchableItems(runtime.searchable.get());
            if (discovered > 0) {
                job.setProgressPercent(Math.min(99, Math.max(job.getProgressPercent(), processed * 100 / discovered)));
            }
        });
    }

    public boolean isStopRequested(String jobId) {
        RuntimeJob runtime = runtimeJobs.get(jobId);
        return runtime == null || runtime.cancelRequested.get() || Thread.currentThread().isInterrupted();
    }

    public boolean requestStop(String jobId) {
        IndexingJob job = repository.findById(jobId).orElse(null);
        if (job == null || !ACTIVE.contains(job.getState()) || !canManage(job)) return false;
        RuntimeJob runtime = runtimeJobs.get(jobId);
        if (runtime != null) runtime.cancelRequested.set(true);
        mutate(jobId, value -> {
            value.setCancelRequested(true);
            value.setState(IndexingJobState.STOPPING);
            value.setStage("Останавливается");
        });
        return true;
    }

    public int requestStopAllVisible() {
        return requestStopAllVisible(null);
    }

    public int requestStopAllVisible(SourceType sourceType) {
        int count = 0;
        for (IndexingJob job : visibleJobs()) {
            if ((sourceType == null || job.getSourceType() == sourceType) && requestStop(job.getId())) count++;
        }
        return count;
    }

    public void complete(String jobId) {
        finish(jobId, IndexingJobState.COMPLETED, "Готово", null, 100);
    }

    public void stopped(String jobId) {
        finish(jobId, IndexingJobState.STOPPED, "Остановлено", null, null);
    }

    public void failed(String jobId, String error) {
        finish(jobId, IndexingJobState.FAILED, "Ошибка", error, null);
    }

    private void finish(String jobId, IndexingJobState state, String stage, String error, Integer progress) {
        RuntimeJob runtime = runtimeJobs.get(jobId);
        if (runtime != null) persistCounters(jobId, runtime);
        mutate(jobId, job -> {
            job.setState(state);
            job.setStage(stage);
            job.setErrorMessage(error);
            if (progress != null) job.setProgressPercent(progress);
            job.setFinishedAt(LocalDateTime.now());
        });
        runtimeJobs.remove(jobId);
    }

    public List<Map<String, Object>> listVisible() {
        return visibleJobs().stream().map(this::toDto).toList();
    }

    public Map<String, Object> summary() {
        List<IndexingJob> jobs = visibleJobs();
        long active = jobs.stream().filter(job -> ACTIVE.contains(job.getState())).count();
        long completed = jobs.stream().filter(job -> job.getState() == IndexingJobState.COMPLETED).count();
        long problems = jobs.stream().filter(job -> job.getState() == IndexingJobState.FAILED
                || job.getState() == IndexingJobState.STOPPED).count();
        int searchable = jobs.stream().mapToInt(job -> {
            RuntimeJob runtime = runtimeJobs.get(job.getId());
            return runtime == null ? job.getSearchableItems() : runtime.searchable.get();
        }).sum();
        return Map.of("total", jobs.size(), "active", active, "completed", completed,
                "problems", problems, "searchableItems", searchable);
    }

    private List<IndexingJob> visibleJobs() {
        return repository.findByOwnerIdOrderByCreatedAtDesc(currentUserService.getUserId());
    }

    private boolean canManage(IndexingJob job) {
        return job.getOwnerId().equals(currentUserService.getUserId());
    }

    private Map<String, Object> toDto(IndexingJob job) {
        RuntimeJob runtime = runtimeJobs.get(job.getId());
        int discovered = runtime == null ? job.getDiscoveredItems()
                : Math.max(runtime.discovered.get(), runtime.processed.get());
        int processed = runtime == null ? job.getProcessedItems() : runtime.processed.get();
        int failed = runtime == null ? job.getFailedItems() : runtime.failed.get();
        int searchable = runtime == null ? job.getSearchableItems() : runtime.searchable.get();
        int progress = job.getProgressPercent();
        if (runtime != null && discovered > 0) {
            progress = Math.min(99, Math.max(progress, processed * 100 / discovered));
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", job.getId());
        dto.put("sourceType", job.getSourceType());
        dto.put("sourceName", job.getSourceName());
        dto.put("sourceUrl", job.getSourceUrl());
        dto.put("state", job.getState());
        dto.put("stage", job.getStage());
        dto.put("progress", progress);
        dto.put("discovered", discovered);
        dto.put("processed", processed);
        dto.put("failed", failed);
        dto.put("searchable", searchable);
        dto.put("partialSearchReady", searchable > 0);
        dto.put("canStop", ACTIVE.contains(job.getState()));
        dto.put("error", job.getErrorMessage());
        dto.put("createdAt", job.getCreatedAt());
        dto.put("updatedAt", job.getUpdatedAt());
        return dto;
    }

    private synchronized void mutate(String jobId, java.util.function.Consumer<IndexingJob> mutation) {
        repository.findById(jobId).ifPresent(job -> {
            mutation.accept(job);
            job.setUpdatedAt(LocalDateTime.now());
            repository.save(job);
        });
    }

    private static final class RuntimeJob {
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicInteger discovered;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger searchable = new AtomicInteger();
        private final AtomicLong lastPersistedNanos = new AtomicLong();
        private volatile Future<?> future;

        private RuntimeJob(int expectedItems) {
            this.discovered = new AtomicInteger(Math.max(0, expectedItems));
        }
    }
}
