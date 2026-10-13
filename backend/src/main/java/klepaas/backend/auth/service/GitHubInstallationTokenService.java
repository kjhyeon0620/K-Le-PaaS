package klepaas.backend.auth.service;

import klepaas.backend.auth.oauth.GitHubAppClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubInstallationTokenService {

    private static final long CACHE_TTL_MS = 55 * 60 * 1000L; // 55분

    private final GitHubAppClient gitHubAppClient;

    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public String getInstallationToken(String owner, String repo) {
        String cacheKey = owner + "/" + repo;
        CachedToken cached = tokenCache.get(cacheKey);

        if (cached != null && !cached.isExpired()) {
            log.debug("Installation token cache hit: {}", cacheKey);
            return cached.token();
        }

        synchronized (this) {
            cached = tokenCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached.token();
            }

            log.info("Installation token cache miss, fetching: {}", cacheKey);
            Long installationId = gitHubAppClient.getInstallationId(owner, repo);
            String token = gitHubAppClient.createInstallationToken(installationId);

            tokenCache.put(cacheKey, new CachedToken(token, Instant.now().plusMillis(CACHE_TTL_MS)));
            log.info("Installation token cached: {}", cacheKey);
            return token;
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
