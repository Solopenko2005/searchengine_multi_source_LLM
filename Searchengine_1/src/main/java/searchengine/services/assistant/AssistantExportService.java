package searchengine.services.assistant;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Service;
import searchengine.dto.assistant.AssistantExportRequest;
import searchengine.dto.assistant.AssistantSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AssistantExportService {

    public byte[] export(AssistantExportRequest request, String format) {
        validate(request);
        if ("txt".equalsIgnoreCase(format)) {
            return asText(request).getBytes(StandardCharsets.UTF_8);
        }
        if (!"docx".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Поддерживаются форматы DOCX и TXT");
        }
        return asDocx(request);
    }

    private byte[] asDocx(AssistantExportRequest request) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            paragraph(document, "Отчёт LLM-ассистента", true, 18);
            if (!request.getQuestion().isBlank()) {
                paragraph(document, "Запрос", true, 13);
                paragraph(document, request.getQuestion(), false, 11);
            }
            paragraph(document, "Ответ", true, 13);
            for (String line : request.getAnswer().split("\\R", -1)) {
                paragraph(document, line.isBlank() ? " " : line, false, 11);
            }
            if (request.getSources() != null && !request.getSources().isEmpty()) {
                paragraph(document, "Использованные источники", true, 13);
                for (AssistantSource source : request.getSources().stream().limit(50).toList()) {
                    StringBuilder value = new StringBuilder("[").append(source.getIndex()).append("] ")
                            .append(safe(source.getTitle()));
                    if (source.getSource() != null && !source.getSource().isBlank()) {
                        value.append(" — ").append(source.getSource());
                    }
                    if (source.getUrl() != null && !source.getUrl().isBlank()) {
                        value.append("\n").append(source.getUrl());
                    }
                    paragraph(document, value.toString(), false, 10);
                }
            }
            document.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось сформировать DOCX", exception);
        }
    }

    private String asText(AssistantExportRequest request) {
        StringBuilder result = new StringBuilder("ОТЧЁТ LLM-АССИСТЕНТА\n\n");
        if (!request.getQuestion().isBlank()) result.append("ЗАПРОС\n").append(request.getQuestion()).append("\n\n");
        result.append("ОТВЕТ\n").append(request.getAnswer()).append("\n");
        List<AssistantSource> sources = request.getSources();
        if (sources != null && !sources.isEmpty()) {
            result.append("\nИСТОЧНИКИ\n");
            sources.stream().limit(50).forEach(source -> result.append('[').append(source.getIndex())
                    .append("] ").append(safe(source.getTitle()))
                    .append(source.getUrl() == null ? "" : " — " + source.getUrl()).append('\n'));
        }
        return result.toString();
    }

    private void paragraph(XWPFDocument document, String value, boolean bold, int size) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Montserrat");
        run.setFontSize(size);
        run.setBold(bold);
        String[] lines = safe(value).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
    }

    private void validate(AssistantExportRequest request) {
        if (request == null || request.getAnswer() == null || request.getAnswer().isBlank()) {
            throw new IllegalArgumentException("Сначала получите ответ ассистента");
        }
        if (request.getAnswer().length() > 100_000) {
            throw new IllegalArgumentException("Ответ слишком большой для экспорта");
        }
        if (request.getQuestion() == null) request.setQuestion("");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
