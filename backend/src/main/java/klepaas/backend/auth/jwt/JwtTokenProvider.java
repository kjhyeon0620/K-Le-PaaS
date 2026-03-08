package klepaas.backend.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import klepaas.backend.auth.dto.TokenResponse;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public TokenResponse createTokens(Long userId, String email, Role role) {
        String accessToken = createToken(userId, email, role, accessTokenExpiry, "access");
        String refreshToken = createToken(userId, email, role, refreshTokenExpiry, "refresh");
        return new TokenResponse(accessToken, refreshToken);
    }

    public String createAccessToken(Long userId, String email, Role role) {
        return createToken(userId, email, role, accessTokenExpiry, "access");
    }

    private String createToken(Long userId, String email, Role role, long expiry, String tokenType) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role.name())
                .claim("type", tokenType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiry))
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    public String getEmail(String token) {
        return getClaims(token).get("email", String.class);
    }

    public Role getRole(String token) {
        return Role.valueOf(getClaims(token).get("role", String.class));
    }

    public void validateRefreshToken(String token, User user) {
        String tokenType = getClaims(token).get("type", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "리프레시 토큰이 아닙니다");
        }
        if (user.getRefreshToken() == null || !token.equals(user.getRefreshToken())) {
            log.warn("Refresh token mismatch for userId={}", user.getId());
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "리프레시 토큰이 일치하지 않습니다");
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
