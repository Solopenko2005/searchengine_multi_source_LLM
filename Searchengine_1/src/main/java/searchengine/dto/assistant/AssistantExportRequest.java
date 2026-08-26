package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AssistantExportRequest {
    private String question = "";
    private String answer = "";
    private List<AssistantSource> sources = new ArrayList<>();
}
