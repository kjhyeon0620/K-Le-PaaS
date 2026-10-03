package klepaas.backend.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.oidc.GitHubActionsIdentity;
import klepaas.backend.auth.oidc.GitHubActionsTokenVerifier;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import klepaas.backend.user.entity.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final CliAccessTokenService cliAccessTokenService;
    private final GitHubActionsTokenVerifier gitHubActionsTokenVerifier;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null) {
            CustomUserDetails userDetails = resolveUser(token);
            if (userDetails != null) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    private CustomUserDetails resolveUser(String token) {
        if (jwtTokenProvider.validateToken(token)) {
            Long userId = jwtTokenProvider.getUserId(token);
            String email = jwtTokenProvider.getEmail(token);
            Role role = jwtTokenProvider.getRole(token);
            return new CustomUserDetails(userId, email, role);
        }

        try {
            return cliAccessTokenService.authenticate(token);
        } catch (Exception e) {
            log.debug("Token authentication failed: {}", e.getMessage());
        }

        // CLI 토큰은 JWT 형식이 아니므로, JWT 형식일 때만 GitHub Actions OIDC 토큰으로 검증한다
        if (token.chars().filter(c -> c == '.').count() == 2) {
            GitHubActionsIdentity identity = gitHubActionsTokenVerifier.verify(token);
            if (identity != null) {
                return CustomUserDetails.gitHubActions(identity);
            }
        }
        return null;
    }
}
