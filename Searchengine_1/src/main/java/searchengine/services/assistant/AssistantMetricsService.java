package searchengine.services.assistant;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Service
public class AssistantMetricsService {
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder retrievalTotal = new LongAdder();
    private final LongAdder generationTotal = new LongAdder();
    private final AtomicLong lastRetrieval = new AtomicLong();
    private final AtomicLong lastGeneration = new AtomicLong();
    private final AtomicLong lastTotal = new AtomicLong();

    public void record(long retrievalMs, long generationMs, long totalMs, boolean failed) {
        requests.increment();
        if (failed) failures.increment();
        retrievalTotal.add(Math.max(0, retrievalMs));
        generationTotal.add(Math.max(0, generationMs));
        lastRetrieval.set(Math.max(0, retrievalMs));
        lastGeneration.set(Math.max(0, generationMs));
        lastTotal.set(Math.max(0, totalMs));
    }

    public Map<String, Object> snapshot() {
        long count = requests.sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requests", count);
        data.put("failures", failures.sum());
        data.put("averageRetrievalMs", count == 0 ? 0 : retrievalTotal.sum() / count);
        data.put("averageGenerationMs", count == 0 ? 0 : generationTotal.sum() / count);
        data.put("lastRetrievalMs", lastRetrieval.get());
        data.put("lastGenerationMs", lastGeneration.get());
        data.put("lastTotalMs", lastTotal.get());
        return data;
    }
}
