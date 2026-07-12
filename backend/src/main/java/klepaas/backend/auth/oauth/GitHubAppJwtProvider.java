package klepaas.backend.auth.oauth;

import io.jsonwebtoken.Jwts;
import klepaas.backend.auth.config.GitHubAppConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GitHubAppJwtProvider {

    private final GitHubAppConfig config;

    private volatile RSAPrivateKey cachedKey;

    public String generateAppJwt() {
        return Jwts.builder()
                .issuer(String.valueOf(config.getAppId()))
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() + 9 * 60_000))
                .signWith(getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    private RSAPrivateKey getPrivateKey() {
        if (cachedKey == null) {
            synchronized (this) {
                if (cachedKey == null) {
                    cachedKey = parsePrivateKey(config.getPrivateKey());
                }
            }
        }
        return cachedKey;
    }

    private RSAPrivateKey parsePrivateKey(String pem) {
        try {
            String normalizedPem = normalizePrivateKey(pem);
            boolean pkcs1RsaKey = normalizedPem.contains("-----BEGIN RSA PRIVATE KEY-----");
            byte[] decoded = Base64.getDecoder().decode(stripPemMarkers(normalizedPem));
            if (pkcs1RsaKey) {
                return generateRsaPrivateKey(wrapPkcs1RsaKey(decoded));
            }

            return generatePkcs8OrPkcs1PrivateKey(decoded);
        } catch (Exception e) {
            throw new IllegalStateException("GitHub App private key 파싱 실패", e);
        }
    }

    private RSAPrivateKey generatePkcs8OrPkcs1PrivateKey(byte[] decoded) throws Exception {
        try {
            return generateRsaPrivateKey(decoded);
        } catch (Exception pkcs8Exception) {
            try {
                return generateRsaPrivateKey(wrapPkcs1RsaKey(decoded));
            } catch (Exception pkcs1Exception) {
                pkcs8Exception.addSuppressed(pkcs1Exception);
                throw pkcs8Exception;
            }
        }
    }

    private RSAPrivateKey generateRsaPrivateKey(byte[] keyBytes) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
        if (privateKey instanceof RSAPrivateKey rsaPrivateKey) {
            return rsaPrivateKey;
        }
        throw new IllegalStateException("RSA private key가 아닙니다");
    }

    private String normalizePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("GitHub App private key가 설정되지 않았습니다");
        }
        String normalized = pem.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n");
    }

    private String stripPemMarkers(String pem) {
        return pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
    }

    private byte[] wrapPkcs1RsaKey(byte[] pkcs1Key) {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] rsaEncryptionAlgorithmIdentifier = new byte[]{
                0x30, 0x0d,
                0x06, 0x09,
                0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        return derSequence(concat(
                version,
                rsaEncryptionAlgorithmIdentifier,
                derOctetString(pkcs1Key)
        ));
    }

    private byte[] derSequence(byte[] value) {
        return concat(new byte[]{0x30}, derLength(value.length), value);
    }

    private byte[] derOctetString(byte[] value) {
        return concat(new byte[]{0x04}, derLength(value.length), value);
    }

    private byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }

        List<Byte> bytes = new ArrayList<>();
        int remaining = length;
        while (remaining > 0) {
            bytes.add(0, (byte) (remaining & 0xff));
            remaining >>= 8;
        }

        byte[] result = new byte[bytes.size() + 1];
        result[0] = (byte) (0x80 | bytes.size());
        for (int i = 0; i < bytes.size(); i++) {
            result[i + 1] = bytes.get(i);
        }
        return result;
    }

    private byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }

        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
