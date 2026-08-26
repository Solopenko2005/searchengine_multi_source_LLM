package searchengine.dto.scientific;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScientificArticleDto {
    private String provider;
    private String externalId;
    private String title;
    private List<String> authors = new ArrayList<>();
    private Integer year;
    private String venue;
    private String abstractText;
    private String url;
    private boolean openAccess;
    private Integer citationCount;
}
