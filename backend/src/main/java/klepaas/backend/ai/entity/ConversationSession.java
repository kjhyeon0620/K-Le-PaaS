package klepaas.backend.ai.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import klepaas.backend.user.entity.User;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "conversation_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String sessionToken;

    @Column(nullable = false)
    private boolean active;

    private LocalDateTime lastActiveAt;

    @Builder
    public ConversationSession(User user) {
        this.user = user;
        this.sessionToken = UUID.randomUUID().toString();
        this.active = true;
        this.lastActiveAt = LocalDateTime.now();
    }

    public void touch() {
        this.lastActiveAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.active = false;
    }
}
