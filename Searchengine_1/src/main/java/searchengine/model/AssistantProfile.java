package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/** Persisted, per-user RAG workspace settings. */
@Getter
@Setter
@Entity
@Table(name = "assistant_profile", uniqueConstraints =
        @UniqueConstraint(name = "uc_assistant_profile_owner", columnNames = "owner_id"))
public class AssistantProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Column(name = "instructions", nullable = false, columnDefinition = "TEXT")
    private String instructions = "";

    /** Comma-separated page identifiers. They are validated against ownership on every use. */
    @Column(name = "document_ids", nullable = false, columnDefinition = "TEXT")
    private String documentIds = "";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
