package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "assistant_chunk", uniqueConstraints =
        @UniqueConstraint(name = "uc_assistant_chunk_page_index", columnNames = {"page_id", "chunk_index"}))
public class AssistantChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "page_id", nullable = false)
    private Page page;

    @Column(name = "site_id", nullable = false)
    private Integer siteId;

    @Column(name = "owner_id", length = 255)
    private String ownerId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "embedding", columnDefinition = "BYTEA")
    private byte[] embedding;

    @Column(name = "embedding_model", length = 255)
    private String embeddingModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssistantChunkStatus status = AssistantChunkStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
