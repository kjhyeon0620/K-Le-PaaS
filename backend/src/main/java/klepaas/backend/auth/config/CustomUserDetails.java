package klepaas.backend.auth.config;

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

    /** 웹 로그인(JWT) 사용자는 사용자 권한 전체를 갖는다. */
    public CustomUserDetails(Long userId, String email, Role role) {
        this(userId, email, role, CliTokenScope.FULL);
    }

    public CustomUserDetails(Long userId, String email, Role role, CliTokenScope scope) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.scope = scope;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()), new SimpleGrantedAuthority(scope.authority()));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
