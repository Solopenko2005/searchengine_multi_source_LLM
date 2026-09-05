package searchengine.services.assistant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class EmbeddingCodecTest {

    @Test
    void preservesVectorValues() {
        float[] source = {0.125f, -1.5f, 0.0f, 42.75f};

        float[] decoded = EmbeddingCodec.decode(EmbeddingCodec.encode(source));

        assertArrayEquals(source, decoded);
    }
}
