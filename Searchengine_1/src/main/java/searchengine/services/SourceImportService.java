package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SourceImportService {

    private static final int MAX_ROWS = 500;
    private static final long MAX_IMPORT_BYTES = 15L * 1024 * 1024;
    private static final Set<String> URL_HEADERS = Set.of("url", "link", "source", "ссылка", "адрес", "источник");
    private static final Set<String> NAME_HEADERS = Set.of("name", "title", "название", "заголовок");
    private static final Set<String> TYPE_HEADERS = Set.of("type", "mode", "тип", "режим");

    private final SiteIndexingService siteIndexingService;

    public Map<String, Object> importSources(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Выберите CSV или XLSX файл");
        if (file.getSize() > MAX_IMPORT_BYTES) throw new IllegalArgumentException("Файл списка не должен превышать 15 МБ");
        String extension = extension(file.getOriginalFilename());
        if (!extension.equals("csv") && !extension.equals("xlsx")) {
            throw new IllegalArgumentException("Поддерживаются списки в форматах CSV и XLSX");
        }

        List<SourceRow> rows;
        try {
            rows = extension.equals("csv") ? readCsv(file.getBytes()) : readXlsx(file.getBytes());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Не удалось прочитать список: " + exception.getMessage(), exception);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("В файле не найдено ни одной ссылки");
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("За один раз можно импортировать не более 500 источников");

        int accepted = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        Set<String> uniqueUrls = new LinkedHashSet<>();
        for (SourceRow row : rows) {
            if (!validUrl(row.url) || !uniqueUrls.add(row.url)) {
                details.add(detail(row, false, "Некорректная или повторяющаяся ссылка"));
                continue;
            }
            ResponseEntity<Map<String, Object>> response = row.site
                    ? siteIndexingService.addSite(row.url, row.name)
                    : siteIndexingService.addPage(row.url, row.name);
            Map<String, Object> body = response.getBody();
            boolean ok = body != null && Boolean.TRUE.equals(body.get("result"));
            if (ok) accepted++;
            details.add(detail(row, ok, body == null ? "Пустой ответ" : String.valueOf(
                    body.getOrDefault(ok ? "message" : "error", ok ? "Принято" : "Ошибка"))));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", accepted > 0);
        result.put("accepted", accepted);
        result.put("failed", rows.size() - accepted);
        result.put("total", rows.size());
        result.put("items", details);
        result.put("message", "Добавлено в очередь: " + accepted + " из " + rows.size());
        return result;
    }

    private List<SourceRow> readCsv(byte[] bytes) throws IOException {
        String text = decodeCsv(bytes);
        List<List<String>> table = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            Character delimiter = null;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (delimiter == null) delimiter = count(line, ';') > count(line, ',') ? ';' : ',';
                table.add(parseCsvLine(line, delimiter));
            }
        }
        return mapRows(table);
    }

    private String decodeCsv(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        return utf8.indexOf('\uFFFD') >= 0 ? new String(bytes, Charset.forName("windows-1251")) : utf8;
    }

    private List<SourceRow> readXlsx(byte[] bytes) throws IOException {
        List<List<String>> table = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru"));
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) return List.of();
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                int last = Math.max(0, row.getLastCellNum());
                for (int column = 0; column < last; column++) {
                    values.add(formatter.formatCellValue(row.getCell(column)).trim());
                }
                if (values.stream().anyMatch(value -> !value.isBlank())) table.add(values);
            }
        }
        return mapRows(table);
    }

    private List<SourceRow> mapRows(List<List<String>> table) {
        if (table.isEmpty()) return List.of();
        List<String> first = table.get(0);
        int urlColumn = findHeader(first, URL_HEADERS);
        boolean header = urlColumn >= 0;
        if (!header) urlColumn = 0;
        int nameColumn = header ? findHeader(first, NAME_HEADERS) : 1;
        int typeColumn = header ? findHeader(first, TYPE_HEADERS) : 2;
        List<SourceRow> result = new ArrayList<>();
        for (int rowIndex = header ? 1 : 0; rowIndex < table.size(); rowIndex++) {
            List<String> row = table.get(rowIndex);
            String url = value(row, urlColumn);
            if (url.isBlank()) continue;
            String name = value(row, nameColumn);
            String type = value(row, typeColumn).toLowerCase(Locale.ROOT);
            boolean site = type.equals("site") || type.equals("website") || type.equals("domain")
                    || type.equals("сайт") || type.equals("домен");
            result.add(new SourceRow(url.trim(), name.trim(), site));
        }
        return result;
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else quoted = !quoted;
            } else if (current == delimiter && !quoted) {
                values.add(value.toString().trim());
                value.setLength(0);
            } else value.append(current);
        }
        values.add(value.toString().trim());
        return values;
    }

    private int findHeader(List<String> row, Set<String> names) {
        for (int index = 0; index < row.size(); index++) {
            if (names.contains(row.get(index).trim().toLowerCase(Locale.ROOT))) return index;
        }
        return -1;
    }

    private String value(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private int count(String value, char character) {
        int result = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == character) result++;
        return result;
    }

    private boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (Exception exception) {
            return false;
        }
    }

    private Map<String, Object> detail(SourceRow row, boolean result, String message) {
        return Map.of("url", row.url, "name", row.name, "mode", row.site ? "site" : "page",
                "result", result, result ? "message" : "error", message);
    }

    private String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private record SourceRow(String url, String name, boolean site) {
    }
}
