package klepaas.backend.deployment.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import klepaas.backend.deployment.entity.DeploymentConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 배포 설정 스냅샷을 만들고 읽는다 (#95). env 값은 서버 비밀키로 만든 HMAC 지문으로만 남긴다.
 * 지문 키는 새 설정 없이 jwt.secret에서 용도 라벨로 파생한다.
 */
@Slf4j
@Component
public class DeploymentConfigSnapshots {

    private static final String KEY_LABEL = "klepaas-deployment-env-fingerprint-v1";
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final byte[] fingerprintKey;
    private final String keyId;

    public DeploymentConfigSnapshots(@Value("${jwt.secret}") String secret) {
        this.fingerprintKey = hmac(secret.getBytes(StandardCharsets.UTF_8), KEY_LABEL);
        this.keyId = hex(hmac(fingerprintKey, "key-id"), 8);
    }

    public DeploymentConfigSnapshot capture(DeploymentConfig config) {
        TreeMap<String, String> envFingerprints = new TreeMap<>();
        config.getEnvVars().forEach((name, value) ->
                envFingerprints.put(name, hex(hmac(fingerprintKey, name + "\u0000" + value), 32)));
        return new DeploymentConfigSnapshot(DeploymentConfigSnapshot.CURRENT_VERSION, keyId,
                config.getBuildStrategy(), config.getImageUriTemplate(),
                config.getMinReplicas(), config.getMaxReplicas(), config.getContainerPort(), config.getDomainUrl(),
                config.getServiceType(), config.getNodePort(), envFingerprints,
                List.copyOf(config.getEnvFromConfigMaps()), List.copyOf(config.getEnvFromSecrets()),
                config.isImagePullSecretEnabled(), config.getImagePullSecretName(),
                config.getHealthProbe(), config.getResources());
    }

    /** 승인 고정용 설정 지문 (#56): 스냅샷 JSON의 SHA-256. env 값은 HMAC 지문으로만 반영된다. */
    public String fingerprint(DeploymentConfig config) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(toJson(capture(config)).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJson(DeploymentConfigSnapshot snapshot) {
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("배포 설정 스냅샷 직렬화 실패", e);
        }
    }

    /** 기록이 없거나 읽을 수 없으면 비어 있다. 읽지 못한 기록을 추정으로 채우지 않는다. */
    public Optional<DeploymentConfigSnapshot> read(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, DeploymentConfigSnapshot.class));
        } catch (Exception e) {
            log.warn("배포 설정 스냅샷을 읽지 못했습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes, int length) {
        return HexFormat.of().formatHex(bytes).substring(0, length);
    }
}
