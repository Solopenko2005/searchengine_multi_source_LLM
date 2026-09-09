package searchengine.services.assistant;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import searchengine.dto.assistant.AssistantExportRequest;
import searchengine.dto.assistant.AssistantSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantExportServiceTest {
    private final AssistantExportService service = new AssistantExportService();

    @Test
    void exportsAnswerAndSourcesAsUtf8Text() {
        AssistantExportRequest request = request();

        String text = new String(service.export(request, "txt"), StandardCharsets.UTF_8);

        assertThat(text).contains("ОТЧЁТ LLM-АССИСТЕНТА", "Как применяется ИИ?",
                "Ответ со ссылкой [1]", "Научная статья", "https://example.test/article");
    }

    @Test
    void exportsReadableDocx() throws Exception {
        byte[] bytes = service.export(request(), "docx");

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String content = document.getParagraphs().stream()
                    .map(paragraph -> paragraph.getText()).reduce("", (a, b) -> a + "\n" + b);
            assertThat(content).contains("Отчёт LLM-ассистента", "Ответ со ссылкой [1]",
                    "Использованные источники");
        }
    }

    @Test
    void rejectsEmptyAnswerAndUnsupportedFormat() {
        assertThatThrownBy(() -> service.export(new AssistantExportRequest(), "txt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Сначала");
        assertThatThrownBy(() -> service.export(request(), "pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DOCX и TXT");
    }

    private AssistantExportRequest request() {
        AssistantExportRequest request = new AssistantExportRequest();
        request.setQuestion("Как применяется ИИ?");
        request.setAnswer("Ответ со ссылкой [1]");
        request.setSources(List.of(new AssistantSource(1, "Научная статья", "Журнал",
                "https://example.test/article", "Фрагмент")));
        return request;
    }
}
