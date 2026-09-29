package klepaas.backend.auth.token.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import klepaas.backend.user.entity.User;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "cli_access_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CliAccessToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 12)
    private String tokenPrefix;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CliTokenScope scope;

    /** DEPLOY 토큰이 배포할 수 있는 저장소. 다른 scope에서는 null */
    @Column(name = "repository_id")
    private Long repositoryId;

    @Builder
    public CliAccessToken(User user, String name, String tokenHash, String tokenPrefix, LocalDateTime expiresAt,
                          CliTokenScope scope, Long repositoryId) {
        this.user = user;
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.expiresAt = expiresAt;
        this.scope = scope == null ? CliTokenScope.FULL : scope;
        this.repositoryId = repositoryId;
    }

    public boolean isUsableAt(LocalDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(LocalDateTime usedAt) {
        this.lastUsedAt = usedAt;
    }

    public void revoke(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }
}
