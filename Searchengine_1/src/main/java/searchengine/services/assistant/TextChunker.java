package searchengine.services.assistant;

import searchengine.config.assistant.AssistantConfig;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {
    private final AssistantConfig config;

    public TextChunker(AssistantConfig config) {
        this.config = config;
    }

    public List<String> split(String source) {
        if (source == null || source.isBlank()) return List.of();
        String text = source.replace('\u00a0', ' ').replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll(" {2,}", " ").trim();
        int size = Math.max(600, config.getEmbedding().getChunkChars());
        int overlap = Math.max(0, Math.min(size / 3, config.getEmbedding().getChunkOverlapChars()));
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf('\n', end), text.lastIndexOf(". ", end));
                if (boundary > start + size / 2) end = boundary + 1;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) chunks.add(chunk);
            if (end >= text.length()) break;
            int next = Math.max(start + 1, end - overlap);
            start = next >= end ? end : next;
        }
        return chunks;
    }
}
