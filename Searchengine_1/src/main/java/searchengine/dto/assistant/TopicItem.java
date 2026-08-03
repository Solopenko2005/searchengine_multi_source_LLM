package searchengine.dto.assistant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Популярная тематика, выявленная по загруженным документам.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopicItem {
    private int rank;
    private String theme;
    private int frequency;
    private int mentions;
    private List<String> sites;
}
