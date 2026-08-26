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
@Table(name = "workspace_group", uniqueConstraints = {
        @UniqueConstraint(name = "uc_workspace_group_invite", columnNames = "invite_code")
})
public class WorkspaceGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "owner_id", nullable = false, length = 255)
    private String ownerId;

    @Column(name = "invite_code", nullable = false, length = 36)
    private String inviteCode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
