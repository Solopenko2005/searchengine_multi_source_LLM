package searchengine.services.assistant;

import org.springframework.stereotype.Service;
import searchengine.config.assistant.AssistantConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Runtime evidence for the assistant SLO. Metrics are kept in a bounded window. */
@Service
public class AssistantMetricsService {
    private final AssistantConfig config;
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder fallbacks = new LongAdder();
    private final LongAdder retrievalTotal = new LongAdder();
    private final LongAdder generationTotal = new LongAdder();
    private final AtomicLong lastRetrieval = new AtomicLong();
    private final AtomicLong lastGeneration = new AtomicLong();
    private final AtomicLong lastTotal = new AtomicLong();
    private final Object samplesLock = new Object();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public AssistantMetricsService(AssistantConfig config) {
        this.config = config;
    }

    public void record(long retrievalMs, long generationMs, long totalMs, boolean failed) {
        record(retrievalMs, generationMs, generationMs, totalMs, failed, failed);
    }

    public void record(long retrievalMs, long generationMs, long timeToFirstTokenMs,
                       long totalMs, boolean failed, boolean fallback) {
        long safeRetrieval = Math.max(0, retrievalMs);
        long safeGeneration = Math.max(0, generationMs);
        long safeTtft = Math.max(0, timeToFirstTokenMs);
        long safeTotal = Math.max(0, totalMs);
        requests.increment();
        if (failed) failures.increment();
        if (fallback) fallbacks.increment();
        retrievalTotal.add(safeRetrieval);
        generationTotal.add(safeGeneration);
        lastRetrieval.set(safeRetrieval);
        lastGeneration.set(safeGeneration);
        lastTotal.set(safeTotal);
        synchronized (samplesLock) {
            samples.addLast(new Sample(safeRetrieval, safeGeneration, safeTtft, safeTotal,
                    failed, fallback));
            int maximum = Math.max(10, config.getSlo().getSampleWindow());
            while (samples.size() > maximum) samples.removeFirst();
        }
    }

    public Map<String, Object> snapshot() {
        long count = requests.sum();
        List<Sample> current;
        synchronized (samplesLock) {
            current = new ArrayList<>(samples);
        }
        long windowFailures = current.stream().filter(Sample::failed).count();
        long windowFallbacks = current.stream().filter(Sample::fallback).count();
        double failureRate = current.isEmpty() ? 0 : (double) windowFailures / current.size();
        double fallbackRate = current.isEmpty() ? 0 : (double) windowFallbacks / current.size();
        List<Sample> llmSamples = current.stream()
                .filter(value -> value.timeToFirstTokenMs() > 0)
                .toList();
        long retrievalP95 = percentile(current, Sample::retrievalMs, 0.95);
        long ttftP95 = percentile(llmSamples, Sample::timeToFirstTokenMs, 0.95);
        long totalP95 = percentile(current, Sample::totalMs, 0.95);

        AssistantConfig.Slo slo = config.getSlo();
        boolean enoughSamples = current.size() >= Math.max(1, slo.getMinimumSamples());
        boolean retrievalPass = retrievalP95 <= slo.getRetrievalP95Millis();
        boolean ttftPass = ttftP95 == 0 || ttftP95 <= slo.getTimeToFirstTokenP95Millis();
        boolean totalPass = totalP95 <= slo.getTotalP95Millis();
        boolean failuresPass = failureRate <= slo.getMaximumFailureRate();
        boolean fallbacksPass = fallbackRate <= slo.getMaximumFallbackRate();
        boolean compliant = enoughSamples && retrievalPass && ttftPass && totalPass
                && failuresPass && fallbacksPass;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requests", count);
        data.put("failures", failures.sum());
        data.put("fallbacks", fallbacks.sum());
        data.put("windowSamples", current.size());
        data.put("windowFailureRate", rounded(failureRate));
        data.put("windowFallbackRate", rounded(fallbackRate));
        data.put("averageRetrievalMs", count == 0 ? 0 : retrievalTotal.sum() / count);
        data.put("averageGenerationMs", count == 0 ? 0 : generationTotal.sum() / count);
        data.put("retrievalP50Ms", percentile(current, Sample::retrievalMs, 0.50));
        data.put("retrievalP95Ms", retrievalP95);
        data.put("retrievalP99Ms", percentile(current, Sample::retrievalMs, 0.99));
        data.put("timeToFirstTokenP50Ms", percentile(llmSamples, Sample::timeToFirstTokenMs, 0.50));
        data.put("timeToFirstTokenP95Ms", ttftP95);
        data.put("generationP95Ms", percentile(current, Sample::generationMs, 0.95));
        data.put("totalP50Ms", percentile(current, Sample::totalMs, 0.50));
        data.put("totalP95Ms", totalP95);
        data.put("totalP99Ms", percentile(current, Sample::totalMs, 0.99));
        data.put("lastRetrievalMs", lastRetrieval.get());
        data.put("lastGenerationMs", lastGeneration.get());
        data.put("lastTotalMs", lastTotal.get());

        Map<String, Object> sloStatus = new LinkedHashMap<>();
        sloStatus.put("state", !enoughSamples ? "insufficient_data" : compliant ? "compliant" : "violated");
        sloStatus.put("compliant", compliant);
        sloStatus.put("minimumSamples", slo.getMinimumSamples());
        sloStatus.put("retrievalP95", check(retrievalPass, retrievalP95, slo.getRetrievalP95Millis()));
        sloStatus.put("timeToFirstTokenP95", check(ttftPass, ttftP95,
                slo.getTimeToFirstTokenP95Millis()));
        sloStatus.put("totalP95", check(totalPass, totalP95, slo.getTotalP95Millis()));
        sloStatus.put("failureRate", Map.of("pass", failuresPass, "actual", rounded(failureRate),
                "maximum", slo.getMaximumFailureRate()));
        sloStatus.put("fallbackRate", Map.of("pass", fallbacksPass, "actual", rounded(fallbackRate),
                "maximum", slo.getMaximumFallbackRate()));
        data.put("slo", sloStatus);
        return data;
    }

    private Map<String, Object> check(boolean pass, long actual, long maximum) {
        return Map.of("pass", pass, "actualMs", actual, "maximumMs", maximum);
    }

    private double rounded(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private long percentile(List<Sample> source, java.util.function.ToLongFunction<Sample> value,
                            double quantile) {
        if (source.isEmpty()) return 0;
        List<Long> values = source.stream().mapToLong(value).boxed()
                .sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(quantile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private record Sample(long retrievalMs, long generationMs, long timeToFirstTokenMs,
                          long totalMs, boolean failed, boolean fallback) {
    }
}
