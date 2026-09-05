package searchengine.services.assistant;

import org.junit.jupiter.api.Test;
import searchengine.config.assistant.AssistantConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    @Test
    void splitsLongTextIntoBoundedOverlappingChunks() {
        AssistantConfig config = new AssistantConfig();
        config.getEmbedding().setChunkChars(700);
        config.getEmbedding().setChunkOverlapChars(100);
        TextChunker chunker = new TextChunker(config);
        String text = "абвгдежз".repeat(350);

        List<String> chunks = chunker.split(text);

        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 700));
        String overlap = chunks.get(0).substring(chunks.get(0).length() - 100);
        assertTrue(chunks.get(1).startsWith(overlap));
    }

    @Test
    void ignoresEmptyInput() {
        assertTrue(new TextChunker(new AssistantConfig()).split("   ").isEmpty());
    }
}
