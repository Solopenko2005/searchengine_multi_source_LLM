package searchengine.dto.assistant;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Популярная тематика, выявленная по загруженным документам.
 */
@Data
@NoArgsConstructor
public class TopicItem {
    private int rank;
    private String theme;
    private int frequency;
    private int mentions;
    private List<String> sites;
    private String description;
    private double confidence;

    public TopicItem(int rank, String theme, int frequency, int mentions, List<String> sites) {
        this(rank, theme, frequency, mentions, sites, "", 0.0);
    }

    public TopicItem(int rank, String theme, int frequency, int mentions, List<String> sites,
                     String description, double confidence) {
        this.rank = rank;
        this.theme = theme;
        this.frequency = frequency;
        this.mentions = mentions;
        this.sites = sites;
        this.description = description;
        this.confidence = confidence;
    }
}
