package searchengine.dto.assistant;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AssistantProfileResponse {
    private boolean result;
    private String instructions;
    private List<Integer> documentIds;
    private List<Integer> sourceIds;
}
