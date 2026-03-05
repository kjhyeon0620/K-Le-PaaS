package klepaas.backend.user.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String providerId; // GitHub ID

    @Deprecated
    private String githubAccessToken;

    @Column(length = 512)
    private String refreshToken;

    @Builder
    public User(String email, String name, Role role, String providerId) {
        this.email = email;
        this.name = name;
        this.role = role;
        this.providerId = providerId;
    }

    @Deprecated
    public void updateGithubAccessToken(String githubAccessToken) {
        this.githubAccessToken = githubAccessToken;
    }

    public void updateRefreshToken(String token) {
        this.refreshToken = token;
    }

    public void revokeRefreshToken() {
        this.refreshToken = null;
    }
}
