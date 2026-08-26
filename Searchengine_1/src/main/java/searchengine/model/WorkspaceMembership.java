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
@Table(name = "workspace_membership", uniqueConstraints = {
        @UniqueConstraint(name = "uc_workspace_membership_user", columnNames = {"group_id", "user_id"})
})
public class WorkspaceMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_membership_group"))
    private WorkspaceGroup group;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;
}
