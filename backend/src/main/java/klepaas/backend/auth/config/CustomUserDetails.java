package klepaas.backend.auth.config;

import klepaas.backend.auth.oidc.GitHubActionsIdentity;
import klepaas.backend.auth.token.entity.CliTokenScope;
import klepaas.backend.user.entity.Role;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Getter
public class CustomUserDetails implements UserDetails {

    private final Long userId;
    private final String email;
    private final Role role;
    private final CliTokenScope scope;
    private final Long deployRepositoryId;
    /** GitHub Actions OIDC로 인증한 주체. 사용자는 없고 배포 생성만 할 수 있다 */
    private final GitHubActionsIdentity gitHubActions;
    // CLI 토큰으로 인증했는지. 웹 JWT와 CLI 전체 권한 토큰은 scope가 같아 배포 요청 경로(#95)를 이것으로 구분한다
    private final boolean cliToken;

    public static final String GITHUB_ACTIONS_AUTHORITY = "SCOPE_GITHUB_ACTIONS";

    /** 웹 로그인(JWT) 사용자는 사용자 권한 전체를 갖는다. */
    public CustomUserDetails(Long userId, String email, Role role) {
        this(userId, email, role, CliTokenScope.FULL, null, false);
    }

    /** CLI 토큰 인증 */
    public CustomUserDetails(Long userId, String email, Role role, CliTokenScope scope, Long deployRepositoryId) {
        this(userId, email, role, scope, deployRepositoryId, true);
    }

    private CustomUserDetails(Long userId, String email, Role role, CliTokenScope scope, Long deployRepositoryId,
                              boolean cliToken) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.scope = scope;
        this.deployRepositoryId = deployRepositoryId;
        this.gitHubActions = null;
        this.cliToken = cliToken;
    }

    private CustomUserDetails(GitHubActionsIdentity gitHubActions) {
        this.userId = null;
        this.email = null;
        this.role = null;
        this.scope = null;
        this.deployRepositoryId = null;
        this.gitHubActions = gitHubActions;
        this.cliToken = false;
    }

    public static CustomUserDetails gitHubActions(GitHubActionsIdentity identity) {
        return new CustomUserDetails(identity);
    }

    public boolean isGitHubActions() {
        return gitHubActions != null;
    }

    /** DEPLOY 토큰은 발급 시 지정한 저장소만 배포할 수 있다. 소유권 검사는 서비스에서 별도로 한다. */
    public boolean canDeployTo(Long repositoryId) {
        return scope == CliTokenScope.FULL
                || (scope == CliTokenScope.DEPLOY && deployRepositoryId != null && deployRepositoryId.equals(repositoryId));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (gitHubActions != null) {
            return List.of(new SimpleGrantedAuthority(GITHUB_ACTIONS_AUTHORITY));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()), new SimpleGrantedAuthority(scope.authority()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return gitHubActions != null ? "github-actions:" + gitHubActions.repository() : email;
    }
}
