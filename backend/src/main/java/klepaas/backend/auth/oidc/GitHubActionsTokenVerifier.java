package klepaas.backend.auth.oidc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * GitHub Actions가 워크플로 실행마다 발급하는 OIDC 토큰을 검증한다 (#65).
 * 서명은 GitHub JWKS로, iss·aud·exp·허용 ref와 repository·sha claim 존재를 확인한다.
 */
@Slf4j
@Component
public class GitHubActionsTokenVerifier {

    public static final String ISSUER = "https://token.actions.githubusercontent.com";

    private final JwtDecoder decoder;

    public GitHubActionsTokenVerifier(
            @Value("${github.actions.oidc.jwks-uri:https://token.actions.githubusercontent.com/.well-known/jwks}") String jwksUri,
            @Value("${github.actions.oidc.audience:k-le-paas}") String audience,
            @Value("${github.actions.oidc.allowed-refs:refs/heads/main}") List<String> allowedRefs) {
        // JWKS 조회가 멈춰 요청 스레드를 붙잡지 않게 시간 제한을 둔다
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .restOperations(new RestTemplate(requestFactory))
                .build();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience mismatch", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                audienceValidator,
                new JwtClaimValidator<String>("ref", ref -> ref != null && allowedRefs.contains(ref)),
                new JwtClaimValidator<String>("repository", StringUtils::hasText),
                new JwtClaimValidator<String>("sha", StringUtils::hasText)));
        this.decoder = nimbus;
    }

    /** 검증에 실패하면 null. 토큰 원문은 로그에 남기지 않는다. */
    public GitHubActionsIdentity verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return new GitHubActionsIdentity(jwt.getClaimAsString("repository"), jwt.getClaimAsString("ref"),
                    jwt.getClaimAsString("sha"));
        } catch (JwtException e) {
            log.debug("GitHub Actions OIDC token rejected: {}", e.getMessage());
            return null;
        }
    }
}
