package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "indexing_job", indexes = {
        @Index(name = "idx_indexing_job_owner", columnList = "owner_id"),
        @Index(name = "idx_indexing_job_state", columnList = "state"),
        @Index(name = "idx_indexing_job_created", columnList = "created_at")
})
public class IndexingJob {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_name", nullable = false, length = 500)
    private String sourceName;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "site_id")
    private Integer siteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IndexingJobState state;

    @Column(nullable = false, length = 80)
    private String stage;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "discovered_items", nullable = false)
    private int discoveredItems;

    @Column(name = "processed_items", nullable = false)
    private int processedItems;

    @Column(name = "failed_items", nullable = false)
    private int failedItems;

    @Column(name = "searchable_items", nullable = false)
    private int searchableItems;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
