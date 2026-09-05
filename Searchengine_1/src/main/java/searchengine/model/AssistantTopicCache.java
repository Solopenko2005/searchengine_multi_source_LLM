package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "assistant_topic_cache")
public class AssistantTopicCache {
    @Id
    @Column(name = "owner_id", length = 255)
    private String ownerId;

    @Column(name = "scope_hash", nullable = false, length = 64)
    private String scopeHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(length = 255)
    private String model;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
