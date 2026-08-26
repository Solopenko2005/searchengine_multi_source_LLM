package searchengine.dto.assistant;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AssistantProfileRequest {
    private String instructions = "";
    private List<Integer> documentIds = new ArrayList<>();
    private List<Integer> sourceIds = new ArrayList<>();
}
